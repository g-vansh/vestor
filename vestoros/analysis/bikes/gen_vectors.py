"""Test vectors for Forecast.java: the Python reference's answers for a handful of situations."""
import json, sys, numpy as np, pandas as pd
from pool import forward, next_bike, quantiles
from model_rates import Model
path = sys.argv[1]; out = sys.argv[2]
M = Model(path)
H = 480
SPAN = 121  # the morning outlook: the chance of an e-bike at 7:30, 8:30 and 9:30
cases = [
    ('class-day morning', '2026-10-20 07:00', 3, 1, [0.0] * 8, [10.0] * 8, None),
    ('both empty', '2026-10-20 08:30', 0, 0, [0.1] * 8, [12.0] * 8, None),
    ('saturday', '2026-10-24 09:00', 6, 2, [0.0] * 8, [14.0] * 8, None),
    ('thanksgiving', '2026-11-26 08:00', 2, 0, [0.0] * 8, [3.0] * 8, None),
    ('rainy morning', '2026-10-21 07:30', 2, 1, [1.0] * 8, [9.0] * 8, None),
    ('overnight outlook', '2026-10-20 22:00', 1, 0, [0.0] * 8, [8.0] * 8, '2026-10-21 07:30'),
    ('evening, both stocked', '2026-10-20 18:00', 8, 3, [0.0] * 8, [12.0] * 8, '2026-10-21 07:30'),
    ('empty at night', '2026-10-22 23:00', 0, 0, [0.2] * 8, [6.0] * 8, '2026-10-23 07:30'),
    ('first dock only', '2026-10-22 07:15', 4, -1, [0.0] * 8, [11.0] * 8, None),
]
cb = M.j.get('calibration', {})
vec = []
for name, t, nv, ne, rain, temp, morning in cases:
    t0 = pd.Timestamp(t)
    minutes = H
    if morning: minutes = max(H, int((pd.Timestamp(morning) - t0).total_seconds() // 60) + SPAN)
    r = M.rates(t0, minutes, rain, temp, empty=nv + max(ne, 0) == 0)
    two = ne >= 0
    if not two:
        for k in ('dE', 'aE', 'rE'): r[k] = np.zeros(minutes)
    v, e = nv, max(ne, 0)
    sizes = (M.j['restock']['vassar']['meanSize'], M.j['restock']['erie']['meanSize'])
    item = {'name': name, 'at': t, 'nV': nv, 'nE': ne, 'rain': rain, 'temp': temp, 'morning': morning}
    if v + e > 0:
        cdf = forward(v, e, r['dV'][:H], r['dE'][:H], r['aV'][:H], r['aE'][:H], r['rV'][:H], r['rE'][:H], 0.0, restock_size=sizes)
        item['pool'] = quantiles(cdf)
        if two and v > 0:
            item['first'] = quantiles(forward(v, e, r['dV'][:H], r['dE'][:H], r['aV'][:H], r['aE'][:H], r['rV'][:H], r['rE'][:H], 0.0,
                                              target='vassar', restock_size=sizes))
    else:
        a = cb.get('arrNext', 1.0)
        item['next'] = quantiles(next_bike(r['aV'][:H] * a, r['aE'][:H] * a, r['rV'][:H], r['rE'][:H]))
    if morning:
        at = int((pd.Timestamp(morning) - t0).total_seconds() // 60)
        _, some = forward(v, e, r['dV'], r['dE'], r['aV'], r['aE'], r['rV'], r['rE'], 0.0, target='none', restock_size=sizes,
                          mode_at=(at, SPAN))
        item['morningP'] = round(float(some[0]), 6)
        item['morningLater'] = [round(float(some[60]), 6), round(float(some[120]), 6)]
    vec.append(item)
    print(name, {k: item.get(k) for k in ('pool', 'first', 'next', 'morningP', 'morningLater')})
json.dump(vec, open(out, 'w'), indent=1)
