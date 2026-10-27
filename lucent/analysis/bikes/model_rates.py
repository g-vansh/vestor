"""Python mirror of Forecast.rates(): per-minute rates from out/bikes_model.json, for backtests and the Java test vectors."""
import json, numpy as np, pandas as pd
DT = ['class', 'finals', 'offweek', 'sat', 'sun']
class Model:
    def __init__(self, path='out/bikes_model.json'):
        self.j = json.load(open(path))
        cal = self.j.get('calendar')
        self.cal_from = pd.Timestamp(cal['from']) if cal else None
        self.cal = cal['days'] if cal else ''
    def daytype(self, ts):
        if self.cal_from is not None:
            n = (ts.normalize() - self.cal_from).days
            if 0 <= n < len(self.cal): return 2 if self.cal[n] == 'h' else 'cfosu'.index(self.cal[n])
        if ts.day_of_week == 5: return 3
        if ts.day_of_week == 6: return 4
        return 2 if ts.month in (1, 6, 7, 8) else 0
    def tbin(self, t):
        if t is None or np.isnan(t): return 1
        b = 0
        for e in self.j['tempEdges']:
            if t >= e: b += 1
        return b
    def holiday(self, ts):
        if self.cal_from is None: return False
        n = (ts.normalize() - self.cal_from).days
        return 0 <= n < len(self.cal) and self.cal[n] == 'h'
    def rates(self, t0, minutes, rain=None, temp=None, empty=False):
        """t0: naive local Timestamp. rain/temp: hourly lists from t0's hour. empty: both docks have no e-bike
        now (the restock calibration for an empty dock applies). Returns dict of per-minute arrays."""
        out = {k: np.zeros(minutes) for k in ('dV', 'dE', 'aV', 'aE', 'rV', 'rE')}
        start_hour = t0.floor('h')
        for i in range(minutes):
            t = t0 + pd.Timedelta(minutes=i)
            dt = self.daytype(t); slot = (t.hour * 60 + t.minute) // 15; mo = t.month - 1
            h = int((t - start_hour) / pd.Timedelta(hours=1))
            p = rain[h] if rain is not None and h < len(rain) and rain[h] is not None and rain[h] >= 0 else 0.12
            tc = temp[h] if temp is not None and h < len(temp) else None
            tb = self.tbin(tc)
            for st, a, b in (('vassar', 'dV', 'aV'), ('erie', 'dE', 'aE')):
                for kind, key in (('dep', a), ('arr', b)):
                    r = self.j['stations'][st][kind]
                    lp = r['base'][DT[dt]][slot] + r['month'][mo] + r['temp'][tb] + (r.get('holiday', 0.0) if self.holiday(t) else 0.0)
                    out[key][i] = np.exp(lp) * (1 - p + p * np.exp(r['wet']))
            key = 'perMinuteWeekend' if dt in (3, 4) and 'perMinuteWeekend' in self.j['restock']['vassar'] else 'perMinute'
            cb = self.j.get('calibration', {})
            dep = cb.get('depWeekend', 1.0) if dt in (3, 4) else cb.get('depWeekday', 1.0)
            out['dV'][i] *= dep; out['dE'][i] *= dep
            rs = cb.get('restockNext', cb.get('restock', 1.0)) if empty else cb.get('restock', 1.0)
            out['rV'][i] = self.j['restock']['vassar'][key][t.hour] * rs
            out['rE'][i] = self.j['restock']['erie'][key][t.hour] * rs
        return out
