# How Lucent predicts the e-bikes

Lucent watches two Bluebikes docks: **MIT Vassar St** in front of Westgate (preferred, it is
closer) and the **Erie St** dock outside Flour in Cambridgeport. You will take an e-bike from
either, so the forecast treats them as one pool and also says when Vassar St alone runs out.

It answers three questions on the bikes card and panel:

| When | It says | Meaning |
|---|---|---|
| Either dock has an e-bike | "Likely all gone by 8:46 AM (8:14 to 9:53)" | When the last e-bike at both docks will be taken. The middle time is the median, the range holds 80% of outcomes |
| Vassar St has one | "Westgate likely empty by 8:31 AM" | When Vassar St alone runs out, so you know whether to walk the extra few minutes |
| Both are empty | "Next e-bike likely around 8:59 AM (8:34 to 10:32)" | The first e-bike to appear at either dock, from a return or Lyft's van |
| Evening and night | "Tomorrow, chance of an e-bike: 77% at 7:30, 69% at 8:30, 60% at 9:30" | The chance at least one is waiting at either dock at those times |

All of it runs on the TV from an 18 KB model file (`assets/bikes_model.json`), at background
priority, every two minutes or whenever a count changes. It takes about 0.1 s on a laptop.

## The data

- **Every Bluebikes trip from March 2024 to September 2026** that starts or ends at a Cambridge
  dock: 6.2 million trips from Bluebikes' monthly files, each with its minute and whether it was
  an e-bike. At Vassar St that is 20,299 e-bike departures and 19,129 e-bike returns. At Erie St,
  5,996 and 5,945.
- **Dock status logs** for Cambridge, 17 September to 27 October 2026: 295,391 snapshots of how
  many e-bikes each dock could rent, from a public logger (AdhamKhouly/bluebikes-logger) that
  polls Bluebikes' live feed, every 10 minutes from 8 October.
- **Hourly weather at Logan** from NOAA (GHCNh station USW00014739): rain, heavy rain (2.5 mm or
  more in an hour), snow, temperature.
- **The academic calendar**, built from the MIT Registrar's key dates for 2022 to 2027, MIT's
  Institute holidays, federal holidays, Boston University's calendar and the Marathon. Every row
  in `calendar/events.csv` names its source. Each day becomes one of: MIT class day, finals
  (with reading days), weekday without MIT classes (IAP, summer, breaks), Saturday, Sunday, plus
  a holiday flag.

## The hard part: an empty dock hides demand

The trip files record the e-bikes people took, never the ones they wanted. When Vassar St is
empty at 8:40, nobody takes an e-bike there, and a naive model learns that nobody wants one at
8:40. That bias is worst exactly when it matters, in the morning rush.

The fix: the trip files do give a lower bound on the stock. Every e-bike returned and not yet
taken is still docked (Lyft's van can only add more). While that bound is at least one, anyone
who wanted an e-bike could take one, so departures in those minutes measure true demand, with
the minutes themselves as exposure. The bound resets at 3:00 each night, when Lyft may collect
bikes for charging. This gives demand rates free of stock-out censoring, and slightly
conservative, since the bound misses e-bikes the van dropped off.

## The model

1. **Demand and returns.** For each dock, a Poisson regression of demand per minute available
   (and of returns per 15 minutes) on the day type crossed with a smooth curve over the time of
   day, plus month, year, holiday, rain, heavy rain, snow and temperature band. Fitted on all
   31 months.
2. **Lyft's van.** Restocks are jumps of four or more e-bikes within 25 minutes in the status
   logs, by hour of day, weekdays apart from weekends, shrunk towards the daily average because
   there are few of them.
3. **The two-dock chain.** Starting from the live counts, Lucent steps both docks forward a
   minute at a time for eight hours: a rider takes an e-bike at a dock that has one, a rider
   returns one, or the van adds a batch. It carries the probability of every pair of counts
   (up to 53 at Vassar St and 19 at Erie St), so the chance that both are empty by each minute
   falls out directly. Hourly chance of rain from the weather forecast mixes the dry and wet
   rates.

**Checked against the alternatives:**

- Gradient-boosted trees given the same inputs, on July to September 2026 held out: the
  regression matched them on Vassar St demand (17.2% of deviance explained vs 17.0%), Vassar St
  returns (18.9% vs 19.4%) and Erie St demand (12.0% vs 12.5%). Trees did better on Erie St
  returns (8.6% vs 4.1%), which only matter while both docks are empty, and that case is
  calibrated separately (below). A day type by hour table, the simplest sensible model, explained
  13.1%, 14.8%, 10.9% and 4.1%. Most 15-minute slots hold zero or one trip, so no model explains
  a large share. The regression stays because it is smooth, small enough to ship, and every
  number in it can be read.
- **Do riders who find one dock empty walk to the other?** A version where they do was scored on
  the September backtest. Error grew from 28 minutes (nobody walks over) to 45 (half do) and 51
  (all do), so the shipped model treats the docks' demand as separate.
- **A boost for recent weeks** (the last eight weeks' level against the fit) made the run-out
  forecasts slightly worse in the backtest, so it is left out.

## What moves the e-bikes: causal estimates

These use designs where the rest of Cambridge stands in for what would have happened otherwise,
so a citywide cause (a heat wave, a transit strike, a holiday) cannot pose as an MIT effect.
E-bike departures at the 7 MIT-area docks (Erie St included) are compared with 82 other
Cambridge docks, with a separate level for every date and every dock (difference in
differences, Poisson, 95% intervals clustered by dock, 674 weekdays). One caveat throughout:
departures stop when a dock is empty, so these are effects on trips that happened. The forecast
itself uses the censoring-free demand above.

| Question | Effect on e-bike departures | 95% interval |
|---|---|---|
| MIT class day vs weekday with no MIT classes | 33% more | 22% to 45% more |
| MIT finals and reading days vs no classes | 27% more | 19% to 34% more |
| Same for classic bikes, class days | 24% more | 17% to 31% more |
| Class days, 6 to 10 AM only | 4% more | 3% fewer to 11% more |
| Class days, 10 AM to 2 PM | 47% more | 27% to 70% more |
| Class days, 2 to 6 PM | 41% more | 26% to 56% more |
| Class days, 6 PM to midnight | 30% more | 22% to 37% more |
| First week of classes vs the rest of term | 6% fewer | 1% to 10% fewer |
| Thanksgiving week, Monday to Wednesday | about the same | 16% fewer to 17% more |
| MIT closed while the city works | 16% fewer | too few days to tell (42% fewer to 23% more) |

**The fall start, week by week** (MIT docks against the rest, relative to three weeks before
Registration Day, pooled over 2024 to 2026): two weeks before, no change (1% more). The week
before, when students move in, 15% more. Registration week 20%, then 26% and 29% in the next
two weeks.

Why the morning looks flat: on class days in the backtest, Vassar St had no e-bike at 8:30 on
about three mornings in four, and neither dock did on about three in five, so extra morning
demand cannot show up as trips. It shows up instead as docks running out earlier,
which is what the forecast predicts.

**Rain**, within the same dock and day (only hours that turned wet that day count, so the kind
of day it was cannot bias it):

| | E-bikes | Classic bikes |
|---|---|---|
| A wet hour, MIT docks | 17% fewer (14% to 20%) | 26% fewer (25% to 27%) |
| Heavy rain, on top of that | 33% fewer again | 40% fewer again |
| A wet hour, all Cambridge | 22% fewer | 29% fewer |

E-bike riders give up less in the rain than classic riders, and heavy rain roughly halves
e-bike trips. Over whole days (the forecast's own terms, which include people deciding in the
morning not to ride) a wet hour cuts e-bike demand at Vassar St by 31%.

**Cold**, by the day's high on MIT class weekdays, against 15 to 20 °C, with the month, the day of week and rain held fixed: below 0 °C, 27% fewer. 0 to 5 °C, 16% fewer. 5 to 10 °C, 6% fewer. 25 to 30
°C, 5% more.

**Boston University's term** while MIT has no classes goes with 18% more e-bike trips at MIT
docks. This was meant as a placebo, but BU's term overlaps IAP and MIT's orientation, when MIT
students are back without classes, so it measures those weeks against summer. The forecast's
month terms already carry that.

## How well it predicts

The backtest uses a model trained only through August 2026, scored on every half hour from 5:00
to 10:00 AM on 34 days (17 to 30 September from exact minute counts rebuilt from trips and
snapshots, 8 to 27 October from the 10-minute log). A forecast still waiting at the end of the
data counts as censored, never as wrong. "Caught" is how often the real time fell inside the 80%
range. A well-calibrated forecast catches about 80% and has an average PIT near 0.5 (below 0.5
means it runs out sooner than predicted).

| Forecast | Starts | Caught by the 80% range | PIT | Typical miss |
|---|---|---|---|---|
| Both docks out, weekdays | 151 | 87% | 0.49 | 31 min |
| Both docks out, weekends | 86 | 71% | 0.40 | 65 min |
| Westgate empty, weekdays | 106 | 81% | 0.53 | 23 min |
| Westgate empty, weekends | 77 | 87% | 0.48 | 37 min |
| Next e-bike, weekdays | 79 | 89% | 0.51 | 20 min |
| Next e-bike, weekends | 14 | 93% | 0.54 | 61 min |

**Tomorrow at 7:30 AM**, predicted at 10 PM the evening before, over 31 mornings (25 had an
e-bike): Brier score 0.141 against 0.167 for always saying the base rate, log loss 0.42 against
0.53. From 6 PM, 0.143 against 0.162.

**Through the morning.** The evening outlook shows the chance at 7:30, 8:30 and 9:30. Checked
from 10 PM on 29 mornings at every half hour from 7:30 to 10:00, it beat the base rate at each
time (Brier 0.135 to 0.227 against 0.153 to 0.268), and its average chance stayed within about
nine points of how often an e-bike was really there (68% forecast against 59% seen at 8:30, the
widest gap). The chance hovers near one half by mid-morning, so a single "gone by" time would
jump around from night to night, and the card gives the three chances instead
(`validate_morning.py`, `results/validate_morning.json`).

**Calibration.** Before it, the next e-bike forecast was too hopeful: it caught 74% on weekdays
(64% at weekends) with PIT 0.65, because a dock empty in the rush gets fewer returns than an
ordinary minute. Multipliers were chosen by leaving each day out in turn and scoring it with
the choice made on the others: returns while both docks are empty count 0.6 of the usual rate
and restocks 0.5. That lifted the held-out log score from -2.83 to -2.74 and gave the table
above. The same search for the run-out forecast made held-out days worse (-3.11 to -3.29), so
run-outs use the model as fitted.

## Limits, plainly

- **Weekend run-outs are the weakest:** the range caught 71%, and the forecast tends to say
  later than it happens. Weekend demand swings with plans and weather more than weekdays do.
- **The backtest knew the weather that happened.** On the TV it uses the forecast chance of rain,
  so rainy-day misses will be somewhat larger than the table.
- **Lyft's van is the least known part.** Forty days of logs hold 3 weekday and 8 weekend
  restocks at Vassar St, 4 and 2 at Erie St. If Lyft changes its rounds, the next e-bike forecast
  will drift until the model is refitted.
- **Few MIT-only closures** in the data, so their effect is uncertain.
- **The calendar runs to June 2027.** After that the TV falls back to treating February to May
  and September to December weekdays as class days. Refit each summer (below) to extend it.

## Rebuilding the model

Python 3 with pandas, numpy, pyarrow, statsmodels, lightgbm and pyfixest. From this folder,
with `work/` and `out/` folders beside the scripts:

1. `fetch_trips.py` and `fetch_status.py` download the trip files and status logs. For weather,
   save NOAA's yearly files as `work/ghcnh_<year>.psv` from
   `https://www.ncei.noaa.gov/oa/global-historical-climatology-network/hourly/access/by-year/<year>/psv/GHCNh_USW00014739_<year>.psv`,
   then run `build_weather.py`. `calendar/build_days.py` turns `calendar/events.csv` into days.
2. `build_events.py`, `build_panel.py` and `reconstruct.py` make the event list, the 15-minute
   panel with exposure, and the exact September stock.
3. `glm.py` compares the regression with trees. `causal.py`, then `causal_calendar.py`, give
   the causal tables (`results/causal.json`).
4. `export_model.py 2026-09-01 work/m_cut.json` fits through August for testing.
   `validate_all.py work/m_cut.json` writes the backtest cases, `fit_calibration.py` chooses the
   multipliers, `eval_models.py` and `validate_extra.py` score everything (`results/`).
5. `export_model.py` on all months, `finalize.py` to add the calibration, and `gen_vectors.py`
   for the TV's test vectors. Copy the model to `assets/bikes_model.json` and the vectors to
   `preview/fixtures/forecast_vectors.json`. `ForecastTest` checks that the TV's Java gives the
   same minutes as this Python, within one.

`live_logger.py` records the two docks every minute, for a longer test set later.
