"""
Censoring-aware backtest on both truth sets: the exact minute stock of 17 to 30 September (trips plus
snapshots) and the 10-minute status log of 8 to 27 October. Every start from 5:00 to 10:00 on the
half hour counts: a run-out (or next e-bike) seen in the data is an event, and one not seen before
the data ends (or within the horizon) is right-censored there. Scores: censored log score (higher is
better), PIT mean over events, and coverage of the 80% band among events and among censored cases.

Optional calibration multipliers (arrivals and restocks for the next-e-bike case, demand for
run-outs) can be passed to see what fits; fit_calibration.py picks them by leave-one-day-out.
"""
import sys, json, numpy as np, pandas as pd
from pool import forward, next_bike, quantiles
from model_rates import Model
H = 420
w = pd.read_parquet('work/weather_hourly.parquet')

def truth_sets():
    st = pd.read_parquet('work/sept_stock.parquet').dropna().rename(columns={'vassar': 'v', 'erie': 'e'})
    s = pd.read_parquet('work/status_all.parquet')
    def series(code):
        x = s[(s.short == code) & (s.t >= '2026-10-08')].sort_values('t').set_index('t').num_ebikes_available
        return x[~x.index.duplicated()]
    oc = pd.concat({'v': series('M32042'), 'e': series('M32047')}, axis=1).sort_index().ffill().dropna()
    oc.index = oc.index.floor('min'); oc = oc[~oc.index.duplicated()]
    return {'sept': st, 'oct': oc}

def cases(weekend=None):
    out = []
    for name, g in truth_sets().items():
        end = g.index.max()
        for day, gg in g.groupby(g.index.date):
            wd = pd.Timestamp(day).day_of_week
            if weekend is not None and (wd >= 5) != weekend: continue
            for hh in (5, 6, 7, 8, 9):
                for mm in (0, 30):
                    t0 = pd.Timestamp(day) + pd.Timedelta(hours=hh, minutes=mm)
                    sel = gg[(gg.index >= t0) & (gg.index < t0 + pd.Timedelta(minutes=10))]
                    if not len(sel): continue
                    ts = sel.index[0]; nv, ne = int(sel.v.iloc[0]), int(sel.e.iloc[0])
                    after = g[g.index > ts]
                    if nv + ne > 0:
                        z = after[(after.v <= 0) & (after.e <= 0)]; kind = 'runout'
                    else:
                        z = after[(after.v > 0) | (after.e > 0)]; kind = 'next'
                    limit = min(H - 1, (end - ts).total_seconds() / 60)
                    if len(z) and (z.index[0] - ts).total_seconds() / 60 <= limit:
                        obs, cens = (z.index[0] - ts).total_seconds() / 60, False
                    else:
                        obs, cens = limit, True
                    out.append(dict(set=name, day=day, ts=ts, nV=nv, nE=ne, kind=kind, obs=obs, cens=cens, weekend=wd >= 5))
    return pd.DataFrame(out)

def predict(M, c, cal):
    t0 = c.ts.floor('min')
    hrs = pd.date_range(t0.floor('h'), periods=8, freq='h'); ww = w.reindex(hrs)
    r = M.rates(t0, H, list(ww.wet.fillna(0.12).astype(float)), list(ww.temp.astype(float)))
    sizes = (M.j['restock']['vassar']['meanSize'], M.j['restock']['erie']['meanSize'])
    if c.kind == 'runout':
        k = cal.get('dep', 1.0); a = cal.get('arr_run', 1.0); rr = cal.get('restock', 1.0)
        return forward(c.nV, c.nE, r['dV'] * k, r['dE'] * k, r['aV'] * a, r['aE'] * a, r['rV'] * rr, r['rE'] * rr, 0.0, restock_size=sizes)
    a = cal.get('arr', 1.0); rr = cal.get('restock', 1.0)
    return next_bike(r['aV'] * a, r['aE'] * a, r['rV'] * rr, r['rE'] * rr)

def score(cdf, obs, cens):
    i = int(min(obs, H - 1))
    if cens:
        return np.log(max(1 - cdf[i], 1e-6))
    # probability of the event in a 10-minute bin around obs (status data is 10-minute)
    lo, hi = max(0, i - 5), min(H - 1, i + 5)
    return np.log(max(cdf[hi] - (cdf[lo - 1] if lo > 0 else 0.0), 1e-6))

def evaluate(M, cs, cal=None, verbose=False):
    cal = cal or {}
    rows = []
    for c in cs.itertuples():
        cdf = predict(M, c, cal)
        lo, med, hi = quantiles(cdf)
        rows.append(dict(kind=c.kind, set=c.set, weekend=c.weekend, cens=c.cens, obs=c.obs, med=med, lo=lo, hi=hi,
                         ls=score(cdf, c.obs, c.cens), pit=cdf[int(min(c.obs, H - 1))], day=c.day))
    r = pd.DataFrame(rows)
    return r

def summary(r, label=''):
    for (kind, we), g in r.groupby(['kind', 'weekend']):
        ev = g[~g.cens]
        inside = ((ev.lo.fillna(-1) <= ev.obs) & (ev.obs <= ev.hi.fillna(1e9))).mean() if len(ev) else float('nan')
        # censored cases: the model should put real weight past the censoring point
        print(f'{label:10s} {kind:6s} {"weekend" if we else "weekday"} n={len(g):3d} events={len(ev):3d} log score {g.ls.mean():.3f} '
              f'PIT {ev.pit.mean():.2f} 80% cover {inside:.2f} median |err| {np.median(np.abs(ev.obs - ev.med.fillna(H))) if len(ev) else float("nan"):.0f} min')

if __name__ == '__main__':
    M = Model(sys.argv[1])
    cs = cases()
    cs.to_parquet('work/cases.parquet')
    print('cases', len(cs), cs.groupby(['kind', 'weekend', 'cens']).size().to_dict())
    r = evaluate(M, cs)
    r.to_parquet('work/eval_base.parquet')
    summary(r, 'base')
