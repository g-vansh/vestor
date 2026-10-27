"""Hourly Boston Logan weather (NOAA GHCNh) in local time: temperature, rain amount, wet and snow flags."""
import pandas as pd, numpy as np
cols=['DATE','temperature','precipitation','wind_speed','relative_humidity','pres_wx_MW1','pres_wx_AU1','pres_wx_AW1','precipitation_Report_Type']
fr=[]
for y in (2023,2024,2025,2026):
    df=pd.read_csv(f'work/ghcnh_{y}.psv',sep='|',low_memory=False)
    fr.append(df[[c for c in cols if c in df.columns]])
w=pd.concat(fr)
w['t']=pd.to_datetime(w.DATE).dt.floor('h').dt.tz_localize('UTC').dt.tz_convert('America/New_York')
w['hour']=w.t.dt.tz_localize(None)  # local wall time, as the trip files use
wx=(w.pres_wx_MW1.fillna('')+' '+w.pres_wx_AU1.fillna('')+' '+w.pres_wx_AW1.fillna(''))
w['wetwx']=wx.str.contains('RA|DZ|SN|SH|TS|PL|GR|UP',regex=True)
w['snowwx']=wx.str.contains('SN|PL',regex=True)
g=w.groupby('hour')
h=pd.DataFrame({'temp':g.temperature.mean(),'precip':g.precipitation.max(),'wind':g.wind_speed.mean(),
                'rh':g.relative_humidity.mean(),'wetwx':g.wetwx.max(),'snowwx':g.snowwx.max()})
h=h.groupby(level=0).mean().asfreq('h')
h['temp']=h.temp.interpolate(limit=6)
h['wind']=h.wind.interpolate(limit=6)
h['precip']=h.precip.fillna(0)
h['wet']=((h.precip>0)|(h.wetwx.fillna(False).astype(bool))).astype(int)
h['snow']=h.snowwx.fillna(False).astype(bool).astype(int)
h=h.drop(columns=['wetwx','snowwx'])
h.to_parquet('work/weather_hourly.parquet')
print(h.describe().T[['count','mean','min','max']])
print('wet share',h.wet.mean(),'span',h.index.min(),h.index.max())
