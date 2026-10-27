"""
More of MIT's calendar, same difference-in-differences design as causal.py (MIT docks vs the other
Cambridge docks, date and dock fixed effects, standard errors clustered by dock), e-bike departures:
1. the term's lift by time of day (does the morning carry it?);
2. the first week of classes against the rest of term;
3. Thanksgiving week's Monday to Wednesday, when classes run but many students have left;
4. Boston University in session while MIT is not (a placebo: BU should not move MIT's docks much).
Adds to out/causal.json.
"""
import json, numpy as np, pandas as pd, warnings
import pyfixest as pf
from features import daytype, calendar
warnings.filterwarnings('ignore')
res = json.load(open('out/causal.json'))
def say(*a):
    print(' '.join(str(x) for x in a), flush=True)
t = pd.read_parquet('work/trips_all.parquet', columns=['rideable_type', 'started_at', 'start_station_id', 'start_station_name'])
t = t[(t.started_at >= '2024-03-01') & (t.started_at < '2026-10-01') & t.start_station_id.fillna('').str.startswith('M3')]
t = t[t.rideable_type == 'electric_bike']
names = t.groupby('start_station_id').start_station_name.agg(lambda s: s.value_counts().index[0])
mit_ids = set(names[names.str.startswith('MIT')].index) | {'M32047'}
t['date'] = t.started_at.dt.normalize()
h = t.started_at.dt.hour
t['band'] = np.select([h < 6, h < 10, h < 14, h < 18], ['night', 'morning', 'midday', 'afternoon'], 'evening')
active = t.groupby('start_station_id').date.nunique()
keep = sorted(active[active > 500].index)
t = t[t.start_station_id.isin(keep)]
bands = ['night', 'morning', 'midday', 'afternoon', 'evening']
cnt = t.groupby(['start_station_id', 'date', 'band']).size()
full = pd.MultiIndex.from_product([keep, pd.date_range('2024-03-01', '2026-09-30'), bands], names=['start_station_id', 'date', 'band'])
d = cnt.reindex(full, fill_value=0).rename('e').reset_index()
d = d[d.date.dt.day_of_week < 5]
dt = daytype(pd.DatetimeIndex(d.date.unique()))
d = d.merge(dt[['daytype', 'first_week', 'bu_classes']], left_on='date', right_index=True)
d['mit'] = d.start_station_id.isin(mit_ids).astype(int)
d['sid'] = d.start_station_id
d['day'] = d.date.dt.strftime('%Y%m%d')
d['sdb'] = d.sid + d.band
d['dayb'] = d.day + d.band
def put(key, m, terms, labels=None):
    tb = m.tidy()
    for i, tn in enumerate(terms):
        b, lo, hi = tb.loc[tn, 'Estimate'], tb.loc[tn, '2.5%'], tb.loc[tn, '97.5%']
        k = f'{key}:{labels[i] if labels else tn}'
        res[k] = {'ratio': round(float(np.exp(b)), 3), 'ci95': [round(float(np.exp(lo)), 3), round(float(np.exp(hi)), 3)], 'p': float(tb.loc[tn, 'Pr(>|t|)'])}
        say(f'{k:36s} x{np.exp(b):.2f}  95% CI {np.exp(lo):.2f} to {np.exp(hi):.2f}')
# 1. term lift by band: dock x band and date x band fixed effects
x = d[d.daytype.isin(['class', 'offweek'])].copy()
for b in bands:
    x[f'mc_{b}'] = x.mit * (x.daytype == 'class') * (x.band == b)
put('term_by_time/ebike', pf.fepois(' + '.join(['e ~ ' + ' + '.join(f'mc_{b}' for b in bands)]) + ' | sdb + dayb', data=x, vcov={'CRV1': 'sid'}),
    [f'mc_{b}' for b in bands], bands)
# daily totals for the rest
y = d.groupby(['sid', 'date', 'day', 'daytype', 'first_week', 'bu_classes', 'mit'], as_index=False).e.sum()
# 2. first week of classes vs the rest of term (class days only)
z = y[y.daytype == 'class'].copy()
z['mit_first'] = z.mit * (z.first_week == 1)
put('first_week/ebike', pf.fepois('e ~ mit_first | sid + day', data=z, vcov={'CRV1': 'sid'}), ['mit_first'])
# 3. Thanksgiving week Monday to Wednesday vs other class days
tg = pd.to_datetime(['2024-11-25', '2024-11-26', '2024-11-27', '2025-11-24', '2025-11-25', '2025-11-26'])
z['mit_tg'] = z.mit * z.date.isin(tg)
put('thanksgiving_week/ebike', pf.fepois('e ~ mit_tg | sid + day', data=z, vcov={'CRV1': 'sid'}), ['mit_tg'])
# 4. BU in session while MIT is off (weekdays without MIT classes)
o = y[y.daytype == 'offweek'].copy()
o['mit_bu'] = o.mit * (o.bu_classes == 1)
if o.mit_bu.sum() > 0:
    put('bu_session_placebo/ebike', pf.fepois('e ~ mit_bu | sid + day', data=o, vcov={'CRV1': 'sid'}), ['mit_bu'])
json.dump(res, open('out/causal.json', 'w'), indent=1)
say('done')
