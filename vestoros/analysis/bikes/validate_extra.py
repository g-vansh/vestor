"""
Two more backtests, on the same truth sets as validate_all (exact minute stock 17 to 30 September,
10-minute status 8 to 27 October), out of sample for a model trained through August:
1. "Westgate likely empty by": the first-passage time to Vassar St alone running out, from every
   5:00 to 10:00 half-hour start with an e-bike there. Censored log score, PIT, 80% coverage.
2. "Tomorrow at 7:30 AM, n% chance of an e-bike": from 22:00 the evening before (and 18:00), the
   chance that either dock has an e-bike at 7:30. Brier score and log loss against the base rate
   of the other mornings (leave one out).
Usage: validate_extra.py model.json out.json
"""
import json, sys, numpy as np, pandas as pd
from multiprocessing import Pool
from validate_all import truth_sets, score, w, H
from pool import forward, quantiles
from model_rates import Model
M = None
def init(path):
    global M
    M = Model(path)
def sizes():
    return (M.j['restock']['vassar']['meanSize'], M.j['restock']['erie']['meanSize'])
def vassar(c):
    t0 = c['ts']
    hrs = pd.date_range(t0.floor('h'), periods=8, freq='h'); ww = w.reindex(hrs)
    r = M.rates(t0, H, list(ww.wet.fillna(0.12).astype(float)), list(ww.temp.astype(float)))
    cdf = forward(c['nV'], c['nE'], r['dV'], r['dE'], r['aV'], r['aE'], r['rV'], r['rE'], 0.0, target='vassar', restock_size=sizes())
    lo, med, hi = quantiles(cdf)
    return dict(c, ls=float(score(cdf, c['obs'], c['cens'])), pit=float(cdf[int(min(c['obs'], H - 1))]), lo=lo, med=med, hi=hi)
def morning(c):
    t0, at = c['ts'], c['at']
    n = int((at - t0).total_seconds() // 60)
    hrs = pd.date_range(t0.floor('h'), periods=n // 60 + 2, freq='h'); ww = w.reindex(hrs)
    empty = c['nV'] + c['nE'] == 0
    r = M.rates(t0, n + 1, list(ww.wet.fillna(0.12).astype(float)), list(ww.temp.astype(float)))
    k = 0.5 if empty else 1.0
    _, some = forward(c['nV'], c['nE'], r['dV'], r['dE'], r['aV'], r['aE'], r['rV'] * k, r['rE'] * k, 0.0, target='none',
                      restock_size=sizes(), mode_at=n)
    return dict(c, p=float(some))
if __name__ == '__main__':
    sets = truth_sets()
    vc, mc = [], []
    for name, g in sets.items():
        end = g.index.max()
        for day, gg in g.groupby(g.index.date):
            for hh in (5, 6, 7, 8, 9):
                for mm in (0, 30):
                    t0 = pd.Timestamp(day) + pd.Timedelta(hours=hh, minutes=mm)
                    sel = gg[(gg.index >= t0) & (gg.index < t0 + pd.Timedelta(minutes=10))]
                    if not len(sel) or int(sel.v.iloc[0]) <= 0: continue
                    ts = sel.index[0]
                    after = g[g.index > ts]; z = after[after.v <= 0]
                    limit = min(H - 1, (end - ts).total_seconds() / 60)
                    if len(z) and (z.index[0] - ts).total_seconds() / 60 <= limit:
                        obs, cens = (z.index[0] - ts).total_seconds() / 60, False
                    else:
                        obs, cens = limit, True
                    vc.append(dict(set=name, day=str(day), ts=ts, nV=int(sel.v.iloc[0]), nE=int(sel.e.iloc[0]), obs=obs, cens=cens,
                                   weekend=pd.Timestamp(day).day_of_week >= 5))
        days = sorted(set(g.index.date))
        for day in days:
            at = pd.Timestamp(day) + pd.Timedelta(hours=7, minutes=30)
            tgt = g[(g.index >= at) & (g.index < at + pd.Timedelta(minutes=10))]
            if not len(tgt): continue
            for start in (18, 22):
                t0 = pd.Timestamp(day) - pd.Timedelta(days=1) + pd.Timedelta(hours=start)
                sel = g[(g.index >= t0) & (g.index < t0 + pd.Timedelta(minutes=10))]
                if not len(sel): continue
                mc.append(dict(set=name, day=str(day), start=start, ts=sel.index[0], at=at, nV=int(sel.v.iloc[0]), nE=int(sel.e.iloc[0]),
                               y=int((tgt.v.iloc[0] > 0) or (tgt.e.iloc[0] > 0)), weekend=pd.Timestamp(day).day_of_week >= 5))
    with Pool(4, initializer=init, initargs=(sys.argv[1],)) as p:
        vr = pd.DataFrame(p.map(vassar, vc, chunksize=4))
        mr = pd.DataFrame(p.map(morning, mc, chunksize=2))
    out = {}
    for we, g in vr.groupby('weekend'):
        ev = g[~g.cens]
        cover = float(((ev.lo.fillna(-1) <= ev.obs) & (ev.obs <= ev.hi.fillna(1e9))).mean()) if len(ev) else None
        out[f'vassar_empty/{"weekend" if we else "weekday"}'] = {'cases': len(g), 'events': len(ev), 'logscore': round(float(g.ls.mean()), 3),
            'pit': round(float(ev.pit.mean()), 3) if len(ev) else None, 'cover80': round(cover, 3) if cover is not None else None,
            'median_abs_err_min': round(float(np.median(np.abs(ev.obs - ev.med.fillna(H))))) if len(ev) else None}
    for start, g in mr.groupby('start'):
        y, p = g.y.values.astype(float), np.clip(g.p.values, 1e-4, 1 - 1e-4)
        base = np.array([(y.sum() - y[i]) / (len(y) - 1) for i in range(len(y))])
        base = np.clip(base, 1e-4, 1 - 1e-4)
        ll = lambda q: float(-np.mean(y * np.log(q) + (1 - y) * np.log(1 - q)))
        out[f'morning_from_{start}h'] = {'mornings': len(g), 'had_ebike': int(y.sum()), 'brier': round(float(np.mean((p - y) ** 2)), 4),
            'brier_base_rate': round(float(np.mean((base - y) ** 2)), 4), 'logloss': round(ll(p), 4), 'logloss_base_rate': round(ll(base), 4),
            'mean_p': round(float(p.mean()), 3)}
        print(start, 'h:', g[['day', 'nV', 'nE', 'p', 'y']].round(2).to_string(index=False), flush=True)
    print(json.dumps(out, indent=1))
    json.dump(out, open(sys.argv[2], 'w'), indent=1)
