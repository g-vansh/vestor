"""
Adds the backtest calibration to the exported model. A multiplier is kept only where choosing it on
the other days beat the uncalibrated model on the held-out day (leave one day out); otherwise 1.
Usage: finalize.py model.json calibration.json out.json
"""
import json, sys
m = json.load(open(sys.argv[1])); c = json.load(open(sys.argv[2]))
cal = {'depWeekday': 1.0, 'depWeekend': 1.0, 'arrNext': 1.0, 'restock': 1.0, 'restockNext': 1.0}
n, r = c['next'], c['runout']
if n['lodo_logscore'] > n['base_logscore']:
    cal['arrNext'] = n['chosen']['arr']; cal['restockNext'] = n['chosen']['restock']
if r['lodo_logscore'] > r['base_logscore']:
    cal['depWeekday'] = r['chosen']['dep_wd']; cal['depWeekend'] = r['chosen']['dep_we']; cal['restock'] = r['chosen']['restock']
m['calibration'] = cal
json.dump(m, open(sys.argv[3], 'w'), separators=(',', ':'))
print('calibration', cal)
