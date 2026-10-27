"""
Scores models on the censoring-aware backtest cases, in parallel: log score, PIT, 80% coverage and
median error by kind and weekday/weekend. Usage: eval_models.py out.json name=model.json[:arr,restock] ...
The optional pair applies calibration to the next-e-bike cases only.
"""
import json, sys, numpy as np, pandas as pd
from multiprocessing import Pool
from validate_all import predict, score, H
from pool import quantiles
from model_rates import Model
M = ROWS = CAL = None
def init(path, cal):
    global M, ROWS, CAL
    M = Model(path); CAL = cal
    ROWS = list(pd.read_parquet('work/cases.parquet').itertuples())
def one(i):
    c = ROWS[i]
    cal = dict(CAL) if c.kind == 'next' else {}
    cdf = predict(M, c, cal)
    lo, med, hi = quantiles(cdf)
    return dict(kind=c.kind, weekend=bool(c.weekend), set=c.set, cens=bool(c.cens), obs=float(c.obs), lo=lo, med=med, hi=hi,
                ls=float(score(cdf, c.obs, c.cens)), pit=float(cdf[int(min(c.obs, H - 1))]), day=str(c.day))
def table(r):
    out = {}
    for (kind, we), g in r.groupby(['kind', 'weekend']):
        ev = g[~g.cens]
        cover = float(((ev.lo.fillna(-1) <= ev.obs) & (ev.obs <= ev.hi.fillna(1e9))).mean()) if len(ev) else None
        err = float(np.median(np.abs(ev.obs - ev.med.fillna(H)))) if len(ev) else None
        out[f'{kind}/{"weekend" if we else "weekday"}'] = {'cases': len(g), 'events': len(ev), 'logscore': round(float(g.ls.mean()), 3),
            'pit': round(float(ev.pit.mean()), 3) if len(ev) else None, 'cover80': round(cover, 3) if cover is not None else None,
            'median_abs_err_min': round(err) if err is not None else None}
    for kind, g in r.groupby('kind'):
        out[f'{kind}/all'] = {'cases': len(g), 'logscore': round(float(g.ls.mean()), 3)}
    return out
if __name__ == '__main__':
    n = len(pd.read_parquet('work/cases.parquet'))
    res = {}
    for spec in sys.argv[2:]:
        name, rest = spec.split('=', 1)
        path, cal = (rest.split(':') + [''])[:2]
        cal = dict(zip(('arr', 'restock'), map(float, cal.split(',')))) if cal else {}
        with Pool(4, initializer=init, initargs=(path, cal)) as p:
            r = pd.DataFrame(p.map(one, range(n), chunksize=4))
        r.to_parquet(f'work/eval_{name}.parquet')
        res[name] = table(r)
        print(name, json.dumps(res[name]), flush=True)
    json.dump(res, open(sys.argv[1], 'w'), indent=1)
