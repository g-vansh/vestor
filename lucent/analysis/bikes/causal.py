"""
Causal questions behind the forecast, each answered with a design that lets the rest of Cambridge
stand in for what would have happened otherwise. Poisson regressions with the fixed effects
absorbed (pyfixest fepois), standard errors clustered by dock.

1. MIT's term (difference in differences): departures at MIT docks vs other Cambridge docks on MIT
   class days vs weekdays without MIT classes (IAP, summer, breaks), with date and dock fixed
   effects, e-bikes and classic bikes separately.
2. Finals, and MIT-only closures (Institute holidays while the city works), same design.
3. Registration Day event study: MIT docks relative to the others, by week around the fall start.
4. Rain, within dock-day: hour-level departures with dock x day and hour fixed effects, so only
   hours that turned wet within the same day identify the effect.
5. Temperature across days: weekday class days, dock and month fixed effects, daily high in bins.
Departures are capped when a dock is empty, so every ratio here is about trips that happened;
the forecast itself uses the exposure-corrected demand.
"""
import json, sys, numpy as np, pandas as pd, warnings
import pyfixest as pf
from features import daytype, calendar
warnings.filterwarnings('ignore')
log = open('out/causal.log', 'w')
def say(*a):
    s = ' '.join(str(x) for x in a)
    print(s, flush=True); log.write(s + '\n'); log.flush()
t = pd.read_parquet('work/trips_all.parquet', columns=['rideable_type', 'started_at', 'start_station_id', 'start_station_name'])
t = t[(t.started_at >= '2024-03-01') & (t.started_at < '2026-10-01') & t.start_station_id.fillna('').str.startswith('M3')]
names = t.groupby('start_station_id').start_station_name.agg(lambda s: s.value_counts().index[0])
mit_ids = set(names[names.str.startswith('MIT')].index) | {'M32047'}  # Erie St (Sidney Research Campus) sits in MIT's orbit
t['date'] = t.started_at.dt.normalize()
t['ebike'] = (t.rideable_type == 'electric_bike').astype(np.int32)
daily = t.groupby(['start_station_id', 'date']).agg(e=('ebike', 'sum'), n=('ebike', 'size')).reset_index()
active = daily.groupby('start_station_id').date.nunique()
keep = sorted(active[active > 600].index)
daily = daily[daily.start_station_id.isin(keep)]
full = pd.MultiIndex.from_product([keep, pd.date_range('2024-03-01', '2026-09-30')], names=['start_station_id', 'date'])
daily = daily.set_index(['start_station_id', 'date']).reindex(full, fill_value=0).reset_index()
daily['c'] = daily.n - daily.e
dt = daytype(pd.DatetimeIndex(daily.date.unique()))
cal = calendar()
daily = daily.merge(dt[['daytype']], left_on='date', right_index=True)
daily['mit'] = daily.start_station_id.isin(mit_ids).astype(int)
daily['sid'] = daily.start_station_id
daily['day'] = daily.date.dt.strftime('%Y%m%d')
wd = daily[daily.date.dt.day_of_week < 5].copy()
c2 = cal.reindex(wd.date)
wd['mit_class'] = wd.mit * (wd.daytype == 'class')
wd['mit_finals'] = wd.mit * (wd.daytype == 'finals')
wd['mit_closed'] = wd.mit * ((c2.mit_holiday.values == 1) & (c2.us_holiday.values == 0))
res = {'docks': {'mit': len(set(keep) & mit_ids), 'other': len(set(keep) - mit_ids)}, 'weekdays': int(wd.date.nunique())}
say('docks: MIT', res['docks']['mit'], 'others', res['docks']['other'], 'weekdays', res['weekdays'])
def report(m, key, terms):
    tb = m.tidy()
    for tn in terms:
        b, lo, hi = tb.loc[tn, 'Estimate'], tb.loc[tn, '2.5%'], tb.loc[tn, '97.5%']
        res[f'{key}:{tn}'] = {'ratio': round(float(np.exp(b)), 3), 'ci95': [round(float(np.exp(lo)), 3), round(float(np.exp(hi)), 3)], 'p': float(tb.loc[tn, 'Pr(>|t|)'])}
        say(f'{key:24s} {tn:12s} x{np.exp(b):.2f}  95% CI {np.exp(lo):.2f} to {np.exp(hi):.2f}')
for y, lab in (('e', 'ebike'), ('c', 'classic')):
    d = wd[wd.daytype.isin(['class', 'offweek', 'finals'])]
    report(pf.fepois(f'{y} ~ mit_class + mit_finals | sid + day', data=d, vcov={'CRV1': 'sid'}), f'term/{lab}', ['mit_class', 'mit_finals'])
sub = wd[(wd.daytype == 'class') | (wd.mit_closed == 1) | ((wd.mit == 0) & wd.date.isin(wd.date[wd.mit_closed == 1]))]
report(pf.fepois('e ~ mit_closed | sid + day', data=sub, vcov={'CRV1': 'sid'}), 'mitclosed/ebike', ['mit_closed'])
# Event study around fall Registration Day (pooled over three years, weeks relative to 3 before)
reg = [pd.Timestamp(d) for d in ('2024-09-03', '2025-09-02', '2026-09-01')]
rows = []
for r in reg:
    win = wd[(wd.date >= r - pd.Timedelta(days=21)) & (wd.date < r + pd.Timedelta(days=21))].copy()
    win['k'] = ((win.date - r).dt.days // 7).astype(int)
    rows.append(win)
ev = pd.concat(rows)
ks = [-2, -1, 0, 1, 2]
for k in ks:
    ev[f'k{k + 3}'] = ev.mit * (ev.k == k)
m = pf.fepois('e ~ ' + ' + '.join(f'k{k + 3}' for k in ks) + ' | sid + day', data=ev, vcov={'CRV1': 'sid'})
tb = m.tidy()
res['registration_event_study'] = {str(k): {'ratio': round(float(np.exp(tb.loc[f'k{k + 3}', 'Estimate'])), 3), 'ci95': [round(float(np.exp(tb.loc[f'k{k + 3}', '2.5%'])), 3), round(float(np.exp(tb.loc[f'k{k + 3}', '97.5%'])), 3)]} for k in ks}
say('event study, MIT vs other e-bike departures relative to 3 weeks before Registration Day:', res['registration_event_study'])
# Rain within dock-day, hour level, 6:00 to 21:59
h = pd.read_parquet('work/weather_hourly.parquet')
for scope, ids in (('mit', sorted(set(keep) & mit_ids)), ('cambridge', keep)):
    tt = t[t.start_station_id.isin(ids)]
    hh = tt.assign(hour=tt.started_at.dt.floor('h')).groupby(['start_station_id', 'hour']).agg(e=('ebike', 'sum'), n=('ebike', 'size'))
    grid = pd.MultiIndex.from_product([ids, pd.date_range('2024-03-01', '2026-09-30 23:00', freq='h')], names=['start_station_id', 'hour'])
    grid = grid[(grid.get_level_values(1).hour >= 6) & (grid.get_level_values(1).hour <= 21)]
    hh = hh.reindex(grid, fill_value=0).reset_index()
    hh = hh.merge(h[['wet', 'precip', 'temp', 'snow']], left_on='hour', right_index=True, how='left').dropna()
    hh['c'] = hh.n - hh.e
    hh['sd'] = hh.start_station_id + hh.hour.dt.strftime('%Y%m%d')
    hh['hr'] = hh.hour.dt.hour.astype(str) + np.where(hh.hour.dt.day_of_week < 5, 'w', 'e')
    hh['heavy'] = (hh.precip >= 2.5).astype(int)
    wetdays = hh.groupby('sd').wet.transform('max') == 1
    hh = hh[wetdays & (hh.snow == 0)]
    say(scope, 'rain sample:', len(hh), 'dock-hours on', hh.sd.nunique(), 'wet dock-days')
    for y, lab in (('e', 'ebike'), ('c', 'classic')):
        report(pf.fepois(f'{y} ~ wet + heavy | sd + hr', data=hh, vcov={'CRV1': 'start_station_id'}), f'rain/{scope}/{lab}', ['wet', 'heavy'])
# Temperature across class weekdays: daily high in bins, dock and year-month fixed effects plus day of week, MIT docks
hi = h.temp.resample('D').max()
cd = wd[(wd.daytype == 'class') & (wd.mit == 1)].copy()
cd['high'] = hi.reindex(cd.date).values
cd = cd.dropna(subset=['high'])
cd['bin'] = pd.cut(cd.high, [-50, 0, 5, 10, 15, 20, 25, 30, 60], labels=['below0', '0to5', '5to10', '10to15', '15to20', '20to25', '25to30', 'above30']).astype(str)
cd['ym'] = cd.date.dt.strftime('%Y%m')
cd['dow'] = cd.date.dt.day_of_week.astype(str)
cd['wetday'] = h.wet.resample('D').max().reindex(cd.date).values
bins = [b for b in ['below0', '0to5', '5to10', '10to15', '20to25', '25to30', 'above30'] if (cd.bin == b).sum() > 30]
for b in bins:
    cd[f'b_{b}'] = (cd.bin == b).astype(int)
m = pf.fepois('e ~ ' + ' + '.join(f'b_{b}' for b in bins) + ' + wetday | sid + ym + dow', data=cd, vcov={'CRV1': 'sid'})
tb = m.tidy()
res['temperature_vs_15to20C'] = {b: {'ratio': round(float(np.exp(tb.loc[f'b_{b}', 'Estimate'])), 3), 'ci95': [round(float(np.exp(tb.loc[f'b_{b}', '2.5%'])), 3), round(float(np.exp(tb.loc[f'b_{b}', '97.5%'])), 3)], 'days': int((cd.bin == b).sum() / max(1, cd.sid.nunique()))} for b in bins}
say('temperature (daily high) vs 15 to 20 C, MIT class weekdays:', res['temperature_vs_15to20C'])
json.dump(res, open('out/causal.json', 'w'), indent=1)
say('done')
