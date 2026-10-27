"""
Exact minute-by-minute e-bike stock at Vassar St and Erie St for 17 to 30 September 2026: each
status snapshot pins the stock, and the trips in between move it. Where a snapshot gap holds a
restock the trips cannot explain, the extra bikes are placed at the latest minute that keeps the
stock from going below zero, or, for a removal, at the gap's end. Then departure demand is
measured against the stock at that dock and at the neighbour.
"""
import numpy as np, pandas as pd
s = pd.read_parquet('work/status_all.parquet')
ev = pd.read_parquet('work/events.parquet')
ev = ev[ev.type == 'electric_bike']
minutes = pd.date_range('2026-09-17', '2026-10-01', freq='1min', inclusive='left')
stock = {}
restocks = []
for code, st in (('M32042', 'vassar'), ('M32047', 'erie')):
    x = s[(s.short == code) & (s.t < '2026-10-01')].sort_values('t')
    e = ev[(ev.station == st) & (ev.t >= '2026-09-17') & (ev.t < '2026-10-01')].copy()
    e['m'] = e.t.dt.floor('min')
    flow = (e.assign(v=np.where(e.kind == 'arr', 1, -1)).groupby('m').v.sum()).reindex(minutes, fill_value=0)
    n = pd.Series(np.nan, index=minutes)
    snaps = list(zip(x.t.dt.floor('min'), x.num_ebikes_available.astype(int)))
    for (t0, n0), (t1, n1) in zip(snaps[:-1], snaps[1:]):
        seg = flow.loc[t0 + pd.Timedelta('1min'):t1]
        path = n0 + seg.cumsum()
        extra = n1 - (path.iloc[-1] if len(path) else n0)
        if extra > 0:
            # a restock of `extra`: it must come before the stock would go negative, so put it just before the lowest point
            low = path.idxmin() if len(path) and path.min() < 0 else (path.index[-1] if len(path) else t1)
            k = path.index.get_loc(low)
            # place it before the first minute the path reaches its minimum below zero
            if path.min() < 0:
                first_neg = path[path < 0].index[0]; k = path.index.get_loc(first_neg)
            path.iloc[k:] += extra
            restocks.append((st, path.index[k], extra))
        elif extra < 0:
            path.iloc[-1:] += extra
            restocks.append((st, path.index[-1], extra))
        n.loc[t0] = n0
        n.loc[path.index] = path.values
    stock[st] = n
df = pd.DataFrame(stock)
df.to_parquet('work/sept_stock.parquet')
r = pd.DataFrame(restocks, columns=['station', 't', 'size'])
r.to_parquet('work/sept_restocks.parquet')
print(r.to_string())
print('minutes with known stock', df.notna().sum().to_dict(), 'negative minutes', (df < 0).sum().to_dict())
# departures by stock state, mornings 6-11 on weekdays
dep = ev[(ev.kind == 'dep') & (ev.t >= '2026-09-17') & (ev.t < '2026-10-01')].copy()
dep['m'] = dep.t.dt.floor('min')
out = []
for st, other in (('vassar', 'erie'), ('erie', 'vassar')):
    d = dep[dep.station == st].groupby('m').size().reindex(minutes, fill_value=0)
    frame = pd.DataFrame({'dep': d, 'n': df[st].shift(1), 'nb': df[other].shift(1)})
    frame = frame[(frame.index.day_of_week < 5) & (frame.index.hour >= 6) & (frame.index.hour < 11)].dropna()
    frame = frame[frame.n >= 1]
    frame['nbin'] = pd.cut(frame.n, [0, 1, 3, 6, 100], labels=['1', '2-3', '4-6', '7+'])
    frame['nb0'] = np.where(frame.nb <= 0, 'neighbour empty', 'neighbour has')
    g = frame.groupby(['nbin', 'nb0'], observed=True).dep.agg(['sum', 'count'])
    g['per_hr'] = g['sum'] / g['count'] * 60
    print('==', st, 'weekday 6-11, departures per hour by own stock and neighbour state')
    print(g.round(2).to_string())
