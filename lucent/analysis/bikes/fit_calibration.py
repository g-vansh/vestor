"""
Picks calibration multipliers by leave-one-day-out cross-validation on the censoring-aware backtest
cases. Next e-bike: a multiplier on returns and one on restocks. Run-out: a demand multiplier for
weekdays and one for weekends, and a restock multiplier. A weekday case only depends on the weekday
demand multiplier (and the weekend one on the weekend), so each case is scored on its own small
grid, in parallel, and the joint grid is put together from those scores.
Usage: fit_calibration.py model.json out.json
"""
import itertools, json, sys, numpy as np, pandas as pd
from multiprocessing import Pool
from validate_all import predict, score
from model_rates import Model
NEXT = {'arr': [0.4, 0.5, 0.6, 0.7, 0.85, 1.0, 1.2], 'restock': [0.5, 0.75, 1.0, 1.5]}
RUN = {'dep_wd': [0.8, 0.9, 1.0, 1.15, 1.3], 'dep_we': [0.8, 1.0, 1.3, 1.6, 2.0], 'restock': [0.5, 0.75, 1.0, 1.5]}
M = ROWS = None
def init(path):
    global M, ROWS
    M = Model(path)
    ROWS = list(pd.read_parquet('work/cases.parquet').itertuples())
def one(i):
    c = ROWS[i]
    out = {}
    if c.kind == 'next':
        for a in NEXT['arr']:
            for rr in NEXT['restock']:
                out[(a, rr)] = score(predict(M, c, {'arr': a, 'restock': rr}), c.obs, c.cens)
    else:
        for k in (RUN['dep_we'] if c.weekend else RUN['dep_wd']):
            for rr in RUN['restock']:
                out[(k, rr)] = score(predict(M, c, {'dep': k, 'restock': rr}), c.obs, c.cens)
    return out
def lodo(S, grid, days):
    held = []
    for d in np.unique(days):
        tr = days != d
        held.extend(S[~tr, int(np.argmax(S[tr].sum(axis=0)))])
    return float(np.mean(held)), grid[int(np.argmax(S.sum(axis=0)))]
if __name__ == '__main__':
    cs = pd.read_parquet('work/cases.parquet')
    rows = list(cs.itertuples())
    with Pool(4, initializer=init, initargs=(sys.argv[1],)) as pool:
        per = pool.map(one, range(len(rows)), chunksize=4)
    res = {}
    for kind in ('next', 'runout'):
        idx = [i for i, c in enumerate(rows) if c.kind == kind]
        if kind == 'next':
            grid = [{'arr': a, 'restock': rr} for a in NEXT['arr'] for rr in NEXT['restock']]
            S = np.array([[per[i][(g['arr'], g['restock'])] for g in grid] for i in idx])
        else:
            grid = [{'dep_wd': a, 'dep_we': b, 'restock': rr} for a in RUN['dep_wd'] for b in RUN['dep_we'] for rr in RUN['restock']]
            S = np.array([[per[i][((g['dep_we'] if rows[i].weekend else g['dep_wd']), g['restock'])] for g in grid] for i in idx])
        base = [j for j, g in enumerate(grid) if all(v == 1.0 for v in g.values())][0]
        days = np.array([str(rows[i].day) for i in idx])
        held, best = lodo(S, grid, days)
        res[kind] = {'cases': len(idx), 'base_logscore': round(float(S[:, base].mean()), 4), 'lodo_logscore': round(held, 4),
                     'chosen': best, 'chosen_insample': round(float(S.sum(axis=0).max() / len(idx)), 4)}
        print(f'== {kind}: n={len(idx)} base {S[:, base].mean():.3f}, held-out with calibration {held:.3f}, chosen {best}', flush=True)
    # one restock multiplier for run-outs and one for the empty dock
    json.dump(res, open(sys.argv[2], 'w'), indent=1)
