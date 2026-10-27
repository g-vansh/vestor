"""
Poisson models of e-bike demand (departures while an e-bike was certainly docked, with that time as
exposure) and of e-bike returns, per dock, on a 15-minute grid: day type x time-of-day spline, month,
year, rain, heavy rain, snow and temperature. Fitted through June 2026 and scored on July to
September 2026 against a day-type average and a gradient-boosted model.
"""
import sys, json, numpy as np, pandas as pd, warnings
import statsmodels.api as sm, statsmodels.formula.api as smf
import lightgbm as lgb
from features import add_features
warnings.filterwarnings('ignore')
p = pd.read_parquet('work/panel.parquet')
p = p[(p.slot_start >= '2024-03-01') & (p.slot_start < '2026-10-01')]
p = add_features(p)
p = p.dropna(subset=['temp'])
SPLIT = '2026-07-01'
F = 'C(daytype) + C(daytype):cc(tod_h, df=14) + C(month) + C(year) + wet + heavy + snow + C(tbin)'
results = {}
for st in sys.argv[1:] or ['vassar', 'erie']:
    q = p[p.station == st]
    for target, off in (('dep_av', 'expo'), ('arr', 'slot')):
        d = q.copy()
        if off == 'expo':
            d = d[d.expo_av > 0.5]
            d['off'] = np.log(d.expo_av / 60.0)
        else:
            d['off'] = np.log(0.25)
        tr, te = d[d.slot_start < SPLIT], d[d.slot_start >= SPLIT]
        te = te[te.year.isin(tr.year.unique())] if False else te
        m = smf.glm(f'{target} ~ {F}', data=tr, family=sm.families.Poisson(), offset=tr.off).fit()
        # 2026 level for the test months
        pred = m.predict(te, offset=te.off)
        def dev(y, mu):
            mu = np.clip(mu, 1e-9, None)
            return 2 * np.sum(np.where(y > 0, y * np.log(y / mu), 0) - (y - mu))
        # baseline: day type x hour-of-day rate from training
        tr2 = tr.assign(h=tr.tod // 4); te2 = te.assign(h=te.tod // 4)
        if off == 'expo':
            b = tr2.groupby(['daytype', 'h']).apply(lambda g: g[target].sum() / np.exp(g.off).sum(), include_groups=False)
        else:
            b = tr2.groupby(['daytype', 'h'])[target].mean() / 0.25
        base = te2.set_index(['daytype', 'h']).index.map(lambda k: b.get(k, np.nan))
        base = np.asarray(base, float) * np.exp(te.off.values)
        base = np.where(np.isnan(base), te[target].mean(), base)
        # boosted trees with the same inputs
        cols = ['tod', 'dow', 'month', 'year', 'wet', 'heavy', 'snow', 'temp', 'wind']
        Xtr = tr[cols].assign(dt=tr.daytype.astype('category').cat.codes)
        Xte = te[cols].assign(dt=pd.Categorical(te.daytype, categories=tr.daytype.astype('category').cat.categories).codes)
        g = lgb.LGBMRegressor(objective='poisson', n_estimators=400, learning_rate=0.03, num_leaves=31, min_child_samples=50,
                              subsample=0.8, subsample_freq=1, colsample_bytree=0.8, verbose=-1)
        g.fit(Xtr, tr[target], init_score=tr.off.values)
        pg = np.exp(g.predict(Xte, raw_score=True) + te.off.values)
        y = te[target].values
        # null: one rate for the whole test period (from training), scaled by each row's exposure
        rate = tr[target].sum() / np.exp(tr.off).sum()
        null = dev(y, rate * np.exp(te.off.values))
        res = {'n_train': len(tr), 'n_test': len(te), 'glm_dev': dev(y, pred.values), 'base_dev': dev(y, base), 'lgb_dev': dev(y, pg),
               'null_dev': null}
        for k in ('glm', 'base', 'lgb'):
            res[k + '_explained'] = 1 - res[k + '_dev'] / null
        results[f'{st}/{target}'] = res
        print(st, target, {k: round(v, 3) if isinstance(v, float) else v for k, v in res.items()}, flush=True)
        params = m.params
        print('   rain x', round(np.exp(params.get('wet', 0)), 3), ' heavy x', round(np.exp(params.get('heavy', 0)), 3),
              ' snow x', round(np.exp(params.get('snow', 0)), 3),
              ' temp:', {k.split('T.')[1].rstrip(']'): round(np.exp(v), 2) for k, v in params.items() if k.startswith('C(tbin)')},
              ' CI rain', np.round(np.exp(m.conf_int().loc['wet'].values), 3))
        m.save(f'work/glm_{st}_{target}.pkl')
json.dump(results, open('work/glm_scores.json', 'w'), indent=1)
