"""Shared features for the 15-minute panel: MIT/BU calendar day type, weather, season, trend."""
import os, numpy as np, pandas as pd
CAL = 'calendar/days.csv'

def calendar():
    if os.path.exists(CAL):
        c = pd.read_csv(CAL, parse_dates=['date'])
        return c.set_index('date')
    return None

def daytype(dates):
    """class / finals / offweek (weekday without MIT classes) / sat / sun, plus flags."""
    d = pd.DatetimeIndex(dates)
    c = calendar()
    out = pd.DataFrame(index=d)
    dow = d.day_of_week
    if c is not None:
        cc = c.reindex(d)
        cls = cc.mit_classes.fillna(0).values == 1
        fin = (cc.mit_finals.fillna(0).values == 1) | (cc.mit_reading.fillna(0).values == 1)
        hol = (cc.mit_holiday.fillna(0).values == 1) | (cc.us_holiday.fillna(0).values == 1)
        out['period'] = cc.mit_period.fillna('unknown').values
        out['first_week'] = cc.mit_first_week.fillna(0).values
        out['bu_classes'] = cc.bu_classes.fillna(0).values
    else:
        # Provisional until the MIT calendar is in: term months, US federal holidays.
        from pandas.tseries.holiday import USFederalHolidayCalendar
        fed = USFederalHolidayCalendar().holidays(d.min(), d.max())
        hol = d.isin(fed)
        cls = (dow < 5) & d.month.isin([2, 3, 4, 5, 9, 10, 11, 12]) & ~hol
        fin = np.zeros(len(d), bool)
        out['period'] = 'unknown'; out['first_week'] = 0; out['bu_classes'] = 0
    t = np.where(dow == 5, 'sat', np.where(dow == 6, 'sun', np.where(fin & ~hol, 'finals', np.where(cls, 'class', 'offweek'))))
    out['daytype'] = t
    out['holiday'] = hol.astype(int)
    return out

def weather():
    w = pd.read_parquet('work/weather_hourly.parquet')
    return w

def add_features(p):
    p = p.copy()
    days = pd.DatetimeIndex(p.date.unique())
    dt = daytype(days)
    p = p.merge(dt, left_on='date', right_index=True, how='left')
    w = weather()
    p['hour'] = p.slot_start.dt.floor('h')
    p = p.merge(w, left_on='hour', right_index=True, how='left')
    p['month'] = p.slot_start.dt.month
    p['year'] = p.slot_start.dt.year
    p['dow'] = p.slot_start.dt.day_of_week
    p['tod_h'] = p.tod / 4.0
    p['tbin'] = pd.cut(p.temp, [-60, 0, 8, 16, 24, 60], labels=['<0', '0-8', '8-16', '16-24', '24+']).astype(str)
    p['wet'] = p.wet.fillna(0)
    p['heavy'] = (p.precip.fillna(0) >= 2.5).astype(int)
    p['snow'] = p.snow.fillna(0)
    return p
