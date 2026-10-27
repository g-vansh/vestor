"""
The evening outlook across the whole morning: from 6 PM and 10 PM the evening before, the chance
that either dock has an e-bike at 7:30, 8:00, ... 10:00, against what the docks showed. Reports
mean forecast vs observed share and the Brier score against the other mornings' base rate.
Usage: validate_morning.py model.json out.json
"""
import json, sys, numpy as np, pandas as pd
from multiprocessing import Pool
from validate_all import truth_sets, w
from pool import forward
from model_rates import Model
TIMES = [(7, 30), (8, 0), (8, 30), (9, 0), (9, 30), (10, 0)]
M = None
def init(path):
    global M
    M = Model(path)
def run(c):
    t0 = c['ts']; at = pd.Timestamp(c['day']) + pd.Timedelta(hours=7, minutes=30)
    n = int((at - t0).total_seconds() // 60); span = 151
    hrs = pd.date_range(t0.floor('h'), periods=(n + span) // 60 + 2, freq='h'); ww = w.reindex(hrs)
    empty = c['nV'] + c['nE'] == 0
    r = M.rates(t0, n + span, list(ww.wet.fillna(0.12).astype(float)), list(ww.temp.astype(float)))
    k = 0.5 if empty else 1.0
    sizes = (M.j['restock']['vassar']['meanSize'], M.j['restock']['erie']['meanSize'])
    _, some = forward(c['nV'], c['nE'], r['dV'], r['dE'], r['aV'], r['aE'], r['rV'] * k, r['rE'] * k, 0.0, target='none',
                      restock_size=sizes, mode_at=(n, span))
    return dict(c, p=[float(some[(h - 7) * 60 + m - 30]) for h, m in TIMES])
if __name__ == '__main__':
    cs = []
    for name, g in truth_sets().items():
        for day in sorted(set(g.index.date)):
            ys = []
            for h, m in TIMES:
                t = pd.Timestamp(day) + pd.Timedelta(hours=h, minutes=m)
                s = g[(g.index >= t) & (g.index < t + pd.Timedelta(minutes=10))]
                ys.append(int((s.v.iloc[0] > 0) or (s.e.iloc[0] > 0)) if len(s) else None)
            if any(y is None for y in ys): continue
            for start in (18, 22):
                t0 = pd.Timestamp(day) - pd.Timedelta(days=1) + pd.Timedelta(hours=start)
                s = g[(g.index >= t0) & (g.index < t0 + pd.Timedelta(minutes=10))]
                if not len(s): continue
                cs.append(dict(day=str(day), start=start, ts=s.index[0], nV=int(s.v.iloc[0]), nE=int(s.e.iloc[0]), y=ys,
                               weekend=pd.Timestamp(day).day_of_week >= 5))
    with Pool(4, initializer=init, initargs=(sys.argv[1],)) as p:
        rs = p.map(run, cs, chunksize=2)
    out = {}
    for start in (18, 22):
        g = [r for r in rs if r['start'] == start]
        rows = []
        for i, (h, m) in enumerate(TIMES):
            y = np.array([r['y'][i] for r in g], float); q = np.array([r['p'][i] for r in g])
            base = np.array([(y.sum() - y[k]) / (len(y) - 1) for k in range(len(y))])
            rows.append({'time': f'{h}:{m:02d}', 'mornings': len(g), 'forecast_mean': round(float(q.mean()), 3), 'observed': round(float(y.mean()), 3),
                         'brier': round(float(np.mean((q - y) ** 2)), 4), 'brier_base_rate': round(float(np.mean((base - y) ** 2)), 4)})
        out[f'from_{start}h'] = rows
        for row in rows: print(start, row, flush=True)
    json.dump(out, open(sys.argv[2], 'w'), indent=1)
