#!/usr/bin/env python3
"""Build days.csv (one row per date, 2022-07-01 to 2027-06-30) from events.csv.

Usage:  python3 build_days.py            (reads/writes next to this file)

Every flag is derived only from rows in events.csv, using these rules:

MIT
  mit_period    first match wins:
                  winter_closure  inside an MIT winter_closure row
                  break           inside a spring-break span (MIT spring_break row widened to the
                                  Saturday before and the Sunday after)
                  fall_term       registration_day_fall .. last day of fall_final_exam_period
                  spring_term     spring_first_day_of_classes .. last day of spring_final_exam_period
                  iap             inside an MIT iap row
                  summer          inside an MIT summer_session row
                  break           everything else (pre-term, post-finals, gap before IAP, etc.)
  mit_classes   Mon-Fri from <term>_first_day_of_classes to <term>_last_day_of_classes (fall and
                spring only), excluding MIT holiday_* rows, student_holiday_no_classes,
                winter_closure and spring_break. monday_schedule_of_classes days are class days.
                IAP and summer session are not "regular classes" and stay 0.
  mit_finals    Mon-Fri inside an MIT fall/spring final_exam_period row (MIT lists weekday exam
                days only, e.g. "May 19, 22-24").
  mit_reading   Mon-Fri strictly after the last day of classes and before the first exam day.
                Derived (MIT publishes no named reading days). Cross-checked against the
                *_reading_days rows in events.csv.
  mit_holiday   MIT holiday_* rows (Institute holidays) and winter_closure rows. Registrar
                "student holiday, no classes" days are NOT Institute holidays (offices open):
                they get mit_classes=0 and a note instead.
  mit_first_week  first five mit_classes days of each fall and spring term.
  mit_spring_break  MIT spring_break rows (Mon-Fri as published).
  mit_commencement_week  MIT commencement_activities, onemit_commencement_ceremony,
                undergraduate_degree_ceremony and advanced_degree_ceremonies rows.
BU
  bu_classes    Mon-Fri from fall/spring first to last day of classes, excluding BU holiday_*,
                thanksgiving_recess and spring_recess rows (summer terms not counted).
  bu_finals     every date inside a BU final_exam_period row (BU publishes only begin/end).
Other
  us_holiday    US_federal holiday_* rows (observed date and, when it differs, the actual
                weekend date) plus MA holiday_* rows (Patriots' Day).
  holiday_name  display names of all holiday_* rows on that date (any institution) plus the
                MIT winter closure, joined with " | ".
  marathon      BAA boston_marathon rows.
  notes         labels of other events on the date, Monday-schedule substitutions, derived
                items, and an "unverified" pointer for any covering row whose notes say so.

The script also checks MIT class-day counts per term against the counts published by MIT
(Bulletin / Registrar class-days page) and exits non-zero on any mismatch.
"""
import csv
import datetime as dt
import os
import sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
EVENTS = os.path.join(HERE, 'events.csv')
DAYS = os.path.join(HERE, 'days.csv')
START, END = dt.date(2022, 7, 1), dt.date(2027, 6, 30)
ONE = dt.timedelta(days=1)

# Published MIT class-day counts: (term, year) -> (Mon, Tue, Wed, Thu, Fri) with Monday-schedule
# days counted as Mondays. Sources: MIT Bulletin archive pages (2022-23 to 2024-25), registrar
# class-days page (2026-27, and 2025 fall as indexed by web search). Spring 2026 not retrieved.
PUBLISHED_CLASS_DAYS = {
    ('fall', 2022): (13, 13, 15, 13, 11),
    ('spring', 2023): (13, 13, 13, 13, 13),
    ('fall', 2023): (13, 13, 15, 13, 11),
    ('spring', 2024): (13, 13, 13, 13, 13),
    ('fall', 2024): (12, 13, 15, 13, 12),
    ('spring', 2025): (13, 13, 13, 13, 13),
    ('fall', 2025): (12, 13, 15, 13, 12),
    ('fall', 2026): (13, 12, 13, 13, 12),
    ('spring', 2027): (13, 13, 13, 13, 13),
}

HOLIDAY_NAMES = {
    'new_years_day': "New Year's Day",
    'mlk_day': 'Martin Luther King Jr. Day',
    'presidents_day': "Presidents' Day (Washington's Birthday)",
    'memorial_day': 'Memorial Day',
    'juneteenth': 'Juneteenth',
    'independence_day': 'Independence Day',
    'labor_day': 'Labor Day',
    'columbus_day': 'Columbus Day',
    'indigenous_peoples_day': "Indigenous Peoples' Day",
    'veterans_day': 'Veterans Day',
    'thanksgiving': 'Thanksgiving Day',
    'day_after_thanksgiving': 'Day after Thanksgiving (MIT Institute holiday)',
    'christmas_day': 'Christmas Day',
    'patriots_day': "Patriots' Day (MA)",
}

# events whose information is already carried by a flag or mit_period, so not repeated in notes
NOTE_SKIP = {
    ('MIT', 'iap'), ('MIT', 'summer_session'), ('MIT', 'winter_closure'), ('MIT', 'spring_break'),
    ('MIT', 'fall_final_exam_period'), ('MIT', 'spring_final_exam_period'),
    ('MIT', 'fall_reading_days'), ('MIT', 'spring_reading_days'),
    ('MIT', 'monday_schedule_of_classes'),
    ('BU', 'summer_term'), ('BU', 'fall_final_exam_period'), ('BU', 'spring_final_exam_period'),
    ('BAA', 'boston_marathon'),
}


def dates(a, b):
    d = a
    while d <= b:
        yield d
        d += ONE


def load():
    rows = []
    with open(EVENTS, newline='') as f:
        for r in csv.DictReader(f):
            r['start'] = dt.date.fromisoformat(r['start_date'])
            r['end'] = dt.date.fromisoformat(r['end_date'])
            if r['end'] < r['start']:
                sys.exit(f"bad range in events.csv: {r}")
            rows.append(r)
    return rows


def holiday_display(event):
    key = event[len('holiday_'):]
    observed = key.endswith('_observed')
    if observed:
        key = key[:-len('_observed')]
    name = HOLIDAY_NAMES.get(key, key.replace('_', ' ').title())
    return name + (' (observed)' if observed else '')


def main():
    rows = load()
    by = defaultdict(list)          # (inst, event) -> rows
    cover = defaultdict(list)       # date -> rows covering it
    for r in rows:
        by[(r['institution'], r['event'])].append(r)
        for d in dates(r['start'], r['end']):
            cover[d].append(r)

    def single(inst, event, term_year, term):
        hits = [r for r in by[(inst, event)] if r['start'].year == term_year]
        if len(hits) != 1:
            sys.exit(f'expected one {inst} {event} in {term} {term_year}, found {len(hits)}')
        return hits[0]

    def in_rows(d, inst, events):
        return any(r['institution'] == inst and r['event'] in events for r in cover[d])

    errors = []

    # ---------------- MIT terms
    mit_terms = []   # dicts: term, year, period_start, first, last, finals_start, finals_end
    for term in ('fall', 'spring'):
        for r in by[('MIT', f'{term}_first_day_of_classes')]:
            y = r['start'].year
            last = single('MIT', f'{term}_last_day_of_classes', y, term)
            fin = single('MIT', f'{term}_final_exam_period', y, term)
            pstart = single('MIT', 'registration_day_fall', y, term)['start'] if term == 'fall' else r['start']
            mit_terms.append(dict(term=term, year=y, period_start=pstart, first=r['start'],
                                  last=last['start'], fin_start=fin['start'], fin_end=fin['end']))
    mit_terms.sort(key=lambda t: t['first'])

    mit_no_class_events = {'student_holiday_no_classes', 'winter_closure', 'spring_break'}
    monday_sched = {r['start'] for r in by[('MIT', 'monday_schedule_of_classes')]}

    def mit_holiday(d):
        return any(r['institution'] == 'MIT' and (r['event'].startswith('holiday_') or r['event'] == 'winter_closure')
                   for r in cover[d])

    mit_classes, mit_finals, mit_reading, mit_first = set(), set(), set(), set()
    for t in mit_terms:
        cls = []
        for d in dates(t['first'], t['last']):
            if d.weekday() >= 5 or mit_holiday(d):
                continue
            if any(r['institution'] == 'MIT' and r['event'] in mit_no_class_events for r in cover[d]):
                continue
            cls.append(d)
        mit_classes.update(cls)
        mit_first.update(cls[:5])
        mit_finals.update(d for d in dates(t['fin_start'], t['fin_end']) if d.weekday() < 5)
        derived = {d for d in dates(t['last'] + ONE, t['fin_start'] - ONE) if d.weekday() < 5 and not mit_holiday(d)}
        mit_reading.update(derived)
        listed = set()
        for r in by[('MIT', f"{t['term']}_reading_days")]:
            if t['last'] < r['start'] < t['fin_start']:
                listed.update(d for d in dates(r['start'], r['end']) if d.weekday() < 5)
        if listed != derived:
            errors.append(f"reading days mismatch {t['term']} {t['year']}: events.csv {sorted(listed)} vs derived {sorted(derived)}")
        # class-day count check (Monday-schedule days count as Mondays)
        counts = [0] * 5
        for d in cls:
            counts[0 if d in monday_sched else d.weekday()] += 1
        t['counts'] = tuple(counts)
        pub = PUBLISHED_CLASS_DAYS.get((t['term'], t['year']))
        status = 'no published count' if pub is None else ('OK' if pub == t['counts'] else f'MISMATCH published {pub}')
        if pub is not None and pub != t['counts']:
            errors.append(f"class-day count mismatch {t['term']} {t['year']}: computed {t['counts']} published {pub}")
        print(f"MIT {t['term']:6} {t['year']}: classes {t['first']}..{t['last']} = {sum(counts):2d} days "
              f"(Mon-schedule-adjusted Mon..Fri {t['counts']}) {status}")
    for d in monday_sched:
        if START <= d <= END and d not in mit_classes:
            errors.append(f'Monday-schedule day {d} is not a class day')

    sb_span = set()
    for r in by[('MIT', 'spring_break')]:
        a = r['start'] - dt.timedelta(days=(r['start'].weekday() - 5) % 7)      # Saturday before
        b = r['end'] + dt.timedelta(days=(6 - r['end'].weekday()) % 7)         # Sunday after
        sb_span.update(dates(a, b))

    def mit_period(d):
        if in_rows(d, 'MIT', {'winter_closure'}):
            return 'winter_closure'
        if d in sb_span:
            return 'break'
        for t in mit_terms:
            if t['period_start'] <= d <= t['fin_end']:
                return f"{t['term']}_term"
        if in_rows(d, 'MIT', {'iap'}):
            return 'iap'
        if in_rows(d, 'MIT', {'summer_session'}):
            return 'summer'
        return 'break'

    # ---------------- BU terms
    bu_classes, bu_finals = set(), set()
    for term in ('fall', 'spring'):
        for r in by[('BU', f'{term}_first_day_of_classes')]:
            y = r['start'].year
            last = single('BU', f'{term}_last_day_of_classes', y, term)
            for d in dates(r['start'], last['start']):
                if d.weekday() >= 5:
                    continue
                if any(x['institution'] == 'BU' and (x['event'].startswith('holiday_') or x['event'] in ('thanksgiving_recess', 'spring_recess'))
                       for x in cover[d]):
                    continue
                bu_classes.add(d)
        for r in by[('BU', f'{term}_final_exam_period')]:
            bu_finals.update(dates(r['start'], r['end']))

    # first-year orientation windows (start .. day before Registration Day) for notes
    orient = {}
    for r in by[('MIT', 'first_year_orientation_begins')]:
        reg = single('MIT', 'registration_day_fall', r['start'].year, 'fall')['start']
        for d in dates(r['start'], reg - ONE):
            orient[d] = r['start']

    # ---------------- assemble
    out = []
    for d in dates(START, END):
        cov = cover[d]
        hol_names, us_hol, notes = [], 0, []
        for r in cov:
            inst, ev = r['institution'], r['event']
            if ev.startswith('holiday_'):
                name = holiday_display(ev)
                if name not in hol_names:
                    hol_names.append(name)
                if inst in ('US_federal', 'MA'):
                    us_hol = 1
        if in_rows(d, 'MIT', {'winter_closure'}):
            hol_names.append('MIT winter break closure')

        if d in monday_sched:
            notes.append('MIT Monday schedule of classes (Tuesday runs Monday classes, counted as class day)')
        if d in mit_reading:
            notes.append('MIT reading day: derived gap between last class and first final exam, unverified label (MIT publishes no named reading days)')
        if d in sb_span and not in_rows(d, 'MIT', {'spring_break'}):
            notes.append('weekend adjacent to MIT spring break (mit_period=break by convention)')
        if d in orient:
            notes.append(f'MIT pre-term, first-year orientation began {orient[d].isoformat()}')
        unver = []
        for r in sorted(cov, key=lambda r: (r['institution'], r['event'])):
            inst, ev = r['institution'], r['event']
            if ev.startswith('holiday_') or inst == 'US_federal' or (inst, ev) in NOTE_SKIP:
                pass
            else:
                label = f"{inst} {ev.replace('_', ' ')}"
                if r['start'] != r['end']:
                    label += f" ({r['start'].isoformat()} to {r['end'].isoformat()})"
                notes.append(label)
            if 'unverified' in r['notes'] and ev not in ('fall_reading_days', 'spring_reading_days'):
                unver.append(f'{inst} {ev}')
        for u in unver:
            notes.append(f'unverified: {u} (see events.csv notes)')

        out.append({
            'date': d.isoformat(),
            'mit_period': mit_period(d),
            'mit_classes': int(d in mit_classes),
            'mit_finals': int(d in mit_finals),
            'mit_reading': int(d in mit_reading),
            'mit_holiday': int(mit_holiday(d)),
            'mit_first_week': int(d in mit_first),
            'mit_spring_break': int(in_rows(d, 'MIT', {'spring_break'})),
            'mit_commencement_week': int(in_rows(d, 'MIT', {'commencement_activities', 'onemit_commencement_ceremony',
                                                             'undergraduate_degree_ceremony', 'advanced_degree_ceremonies'})),
            'bu_classes': int(d in bu_classes),
            'bu_finals': int(d in bu_finals),
            'us_holiday': us_hol,
            'holiday_name': ' | '.join(hol_names),
            'marathon': int(in_rows(d, 'BAA', {'boston_marathon'})),
            'notes': ' | '.join(notes),
        })

    # sanity checks
    for o in out:
        if o['mit_classes'] and (o['mit_holiday'] or o['mit_finals'] or o['mit_reading'] or o['mit_spring_break']):
            errors.append(f"conflicting MIT flags on {o['date']}")
        if o['bu_classes'] and o['bu_finals']:
            errors.append(f"conflicting BU flags on {o['date']}")
    if len(out) != (END - START).days + 1:
        errors.append('row count mismatch')

    cols = ['date', 'mit_period', 'mit_classes', 'mit_finals', 'mit_reading', 'mit_holiday', 'mit_first_week',
            'mit_spring_break', 'mit_commencement_week', 'bu_classes', 'bu_finals', 'us_holiday', 'holiday_name',
            'marathon', 'notes']
    with open(DAYS, 'w', newline='') as f:
        w = csv.DictWriter(f, fieldnames=cols)
        w.writeheader()
        w.writerows(out)
    print(f'wrote {len(out)} rows to {DAYS}')
    if errors:
        print('ERRORS:')
        for e in errors:
            print('  ' + e)
        sys.exit(1)


if __name__ == '__main__':
    main()
