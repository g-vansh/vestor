"""
Reference predictor for the two-dock e-bike pool (Vassar St first, Erie St too), the same model the
TV's Forecast.java runs. The joint stock (nV, nE) moves minute by minute with at most one event a
minute: a rider takes an e-bike at a dock that has one, a rider returns one, or Lyft's van adds a
batch (geometric size). forward() gives the first-passage CDF of the pool running out (or of
Vassar alone), and next_bike() the waiting time for the next e-bike with both empty.
"""
import numpy as np

def kernel(mean, cap):
    p = 1.0 / max(1.0, mean)
    k = np.zeros(cap + 1)
    k[1:] = p * (1 - p) ** (np.arange(1, cap + 1) - 1)
    return k / k.sum()

def forward(nV, nE, dV, dE, aV, aE, rV, rE, theta=0.0, capV=53, capE=19, target='pool', restock_size=(8.0, 4.0), mode_at=None):
    H = len(dV)
    nv, ne = capV + 1, capE + 1
    P = np.zeros((nv, ne)); P[min(nV, capV), min(nE, capE)] = 1
    kV = kernel(restock_size[0], capV); kE = kernel(restock_size[1], capE)
    hasV = (np.arange(nv) > 0)[:, None] * np.ones((1, ne)); hasE = np.ones((nv, 1)) * (np.arange(ne) > 0)[None, :]
    cdf = np.zeros(H); done = 0.0; some = None
    for m in range(H):
        dv = dV[m] + theta * dE[m] * (1 - hasE); de = dE[m] + theta * dV[m] * (1 - hasV)
        rv_dep = dv * hasV; re_dep = de * hasE
        total = rv_dep + re_dep + aV[m] + aE[m] + rV[m] + rE[m]
        pe = 1 - np.exp(-total)
        f = np.where(total > 0, P * pe / np.where(total > 0, total, 1), 0)
        N = P * (1 - pe)
        N[:-1, :] += (f * rv_dep)[1:, :]
        N[:, :-1] += (f * re_dep)[:, 1:]
        N[1:, :] += (f * aV[m])[:-1, :]; N[-1, :] += (f * aV[m])[-1, :]
        N[:, 1:] += (f * aE[m])[:, :-1]; N[:, -1] += (f * aE[m])[:, -1]
        if rV[m] > 0:
            for k in range(1, nv):
                w = kV[k]
                if w <= 1e-6: break
                N[k:, :] += (f * rV[m] * w)[:-k, :]
                N[-1, :] += (f * rV[m] * w)[-k:, :].sum(axis=0)
        if rE[m] > 0:
            for k in range(1, ne):
                w = kE[k]
                if w <= 1e-6: break
                N[:, k:] += (f * rE[m] * w)[:, :-k]
                N[:, -1] += (f * rE[m] * w)[:, -k:].sum(axis=1)
        if target == 'pool':
            done += N[0, 0]; N[0, 0] = 0
        elif target == 'vassar':
            done += N[0, :].sum(); N[0, :] = 0
        if mode_at is not None and m == mode_at:
            some = 1 - N[0, 0]
        cdf[m] = done
        P = N
    return (cdf, some) if mode_at is not None else cdf

def next_bike(aV, aE, rV, rE):
    haz = np.cumsum(aV + aE + rV + rE)
    return 1 - np.exp(-haz)

def quantiles(cdf, qs=(0.1, 0.5, 0.9)):
    out = []
    for q in qs:
        i = int(np.searchsorted(cdf, q))
        out.append(i if i < len(cdf) else None)
    return out
