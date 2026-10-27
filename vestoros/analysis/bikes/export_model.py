"""
Fits the final demand and return models on every month (March 2024 to September 2026), measures
Lyft's restock hazard from the status logs, and writes the compact model the TV runs:
per dock and kind, a log-rate per minute for each day type and 15-minute slot (January, dry, 0 to
8 degrees, 2026), plus additive month, rain, heavy rain, snow and temperature terms.
"""
import json, sys, numpy as np, pandas as pd, warnings
import statsmodels.api as sm, statsmodels.formula.api as smf
from features import add_features, calendar
warnings.filterwarnings('ignore')
DAYTYPES = ['class', 'finals', 'offweek', 'sat', 'sun']
TBINS = ['<0', '0-8', '8-16', '16-24', '24+']
F = 'C(daytype) + C(daytype):cc(tod_h, df=14) + C(month) + C(year) + holiday + wet + heavy + snow + C(tbin, Treatment("0-8"))'
p = pd.read_parquet('work/panel.parquet')
CUT = sys.argv[1] if len(sys.argv) > 1 else '2026-10-01'
OUT = sys.argv[2] if len(sys.argv) > 2 else 'out/bikes_model.json'
RECENT = len(sys.argv) > 3 and sys.argv[3] == 'recent'
p = p[(p.slot_start >= '2024-03-01') & (p.slot_start < CUT)]
p = add_features(p).dropna(subset=['temp'])
out = {'version': 1, 'trained': '2024-03-01/2026-09-30', 'slotMinutes': 15, 'dayTypes': DAYTYPES, 'tempBins': TBINS,
       'tempEdges': [0, 8, 16, 24], 'stations': {}}
for st in ('vassar', 'erie'):
    q = p[p.station == st]
    out['stations'][st] = {}
    for kind, target in (('dep', 'dep_av'), ('arr', 'arr')):
        d = q.copy()
        if kind == 'dep':
            d = d[d.expo_av > 0.5]; d['off'] = np.log(d.expo_av / 60.0)
        else:
            d['off'] = np.log(0.25)
        m = smf.glm(f'{target} ~ {F}', data=d, family=sm.families.Poisson(), offset=d.off).fit()
        have = [dt for dt in DAYTYPES if dt in set(d.daytype)]
        grid = pd.DataFrame([(dt, k) for dt in have for k in range(96)], columns=['daytype', 'tod'])
        grid['tod_h'] = (grid.tod + 0.5) / 4.0
        grid['month'] = 1; grid['year'] = 2026; grid['wet'] = 0; grid['heavy'] = 0; grid['snow'] = 0; grid['tbin'] = '0-8'; grid['holiday'] = 0
        lp = np.log(m.predict(grid, offset=np.full(len(grid), np.log(1 / 60.0))))  # per minute
        # How the last weeks before the cut compare with the fit, month by month and over 8 weeks.
        fitted = m.predict(d, offset=d.off)
        recent = d.slot_start >= pd.Timestamp(CUT) - pd.Timedelta(days=56)
        obs_r, fit_r = float(d.loc[recent, target].sum()), float(fitted[recent].sum())
        by_month = d.assign(f=fitted).groupby(d.slot_start.dt.to_period('M')).agg(y=(target, 'sum'), f=('f', 'sum')).tail(6)
        print(st, kind, 'observed/fitted by month:', {str(k): round(v.y / v.f, 2) for k, v in by_month.iterrows()},
              'last 8 weeks %.0f/%.0f' % (obs_r, fit_r), flush=True)
        level = 0.0
        if RECENT:
            # shrink towards 1 with 60 pseudo-events, then fold into the base rates
            level = float(np.log((obs_r + 60) / (fit_r + 60)))
            lp = lp + level
        base = {dt: [round(float(v), 4) for v in lp[grid.daytype == (dt if dt in have else 'class')]] for dt in DAYTYPES}
        pr = m.params
        month = [0.0] + [round(float(pr.get(f'C(month)[T.{k}]', 0.0)), 4) for k in range(2, 13)]
        temp = [round(float(pr.get(f'C(tbin, Treatment("0-8"))[T.{b}]', 0.0)), 4) for b in TBINS]
        out['stations'][st][kind] = {'base': base, 'month': month, 'wet': round(float(pr['wet']), 4), 'holiday': round(float(pr['holiday']), 4),
                                     'heavy': round(float(pr['heavy']), 4), 'snow': round(float(pr['snow']), 4), 'temp': temp,
                                     'recentLevel': round(level, 4)}
        print(st, kind, 'rain x%.2f heavy x%.2f' % (np.exp(pr['wet']), np.exp(pr['heavy'])), flush=True)
# Restocks: jumps of 4 or more e-bikes within 25 minutes in the status logs, by hour of day, kept
# apart for weekdays and weekends (Lyft's van mostly comes on weekends and in the evening).
s = pd.read_parquet('work/status_all.parquet')
out['restock'] = {}
for code, st in (('M32042', 'vassar'), ('M32047', 'erie')):
    x = s[(s.short == code)].sort_values('t').drop_duplicates('t')
    x['d'] = x.num_ebikes_available.diff(); x['gap'] = x.t.diff().dt.total_seconds() / 60
    dense = x[x.gap <= 25].copy()
    dense['we'] = dense.t.dt.day_of_week >= 5
    jumps = dense[dense.d >= 4]
    res = {}
    total_events = len(jumps); total_hours = dense.gap.sum() / 60.0
    overall = total_events / max(total_hours, 1)
    for key, flag in (('perMinute', False), ('perMinuteWeekend', True)):
        dd = dense[dense.we == flag]; jj = jumps[jumps.t.dt.day_of_week.ge(5) == flag]
        by_hour = np.zeros(24); expo_hour = np.zeros(24)
        for t in jj.t: by_hour[t.hour] += 1
        for t, gmin in zip(dd.t, dd.gap): expo_hour[t.hour] += gmin / 60.0
        k = np.array([1, 2, 3, 2, 1], float); k /= k.sum()
        num = np.convolve(np.r_[by_hour[-2:], by_hour, by_hour[:2]], k, 'valid')
        den = np.convolve(np.r_[expo_hour[-2:], expo_hour, expo_hour[:2]], k, 'valid')
        # shrink towards this day group's own all-day rate, then lightly towards the overall one
        group = (len(jj) + 2 * overall * 24) / (dd.gap.sum() / 60.0 + 48)
        prior = 30.0
        rate_h = (num + prior * group) / (den + prior)
        res[key] = [round(float(r / 60.0), 7) for r in rate_h]
        print(st, key, 'events', len(jj), 'hours', round(dd.gap.sum() / 60.0), 'per day %.2f' % (rate_h.sum()))
    sizes = jumps.d.values
    res.update({'meanSize': round(float(sizes.mean()) if len(sizes) else 6.0, 2), 'events': int(total_events),
                'hoursObserved': round(float(total_hours), 1)})
    out['restock'][st] = res
# Calendar: MIT day types from the dataset, for the days the TV will see.
c = calendar()
if c is not None:
    from features import daytype
    days = pd.date_range('2026-10-01', '2027-06-30', freq='D')
    dt = daytype(days)
    # 'h' marks a weekday holiday (no MIT classes and the holiday term applies)
    chars = ['h' if (v == 'offweek' and hol == 1) else {'class': 'c', 'finals': 'f', 'offweek': 'o', 'sat': 's', 'sun': 'u'}[v]
             for v, hol in zip(dt.daytype, dt.holiday)]
    out['calendar'] = {'from': '2026-10-01', 'to': '2027-06-30', 'days': ''.join(chars)}
out['trained'] = '2024-03-01/' + CUT
json.dump(out, open(OUT, 'w'), separators=(',', ':'))
print('written', len(json.dumps(out, separators=(',', ':'))), 'bytes')
