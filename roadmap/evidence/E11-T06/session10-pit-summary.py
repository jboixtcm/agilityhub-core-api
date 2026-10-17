#!/usr/bin/env python3
"""One line per mutation run of the current gate (unit pass 152 + session-10 integration supplements).

For each run: its recorded exit, the host-lock wait, PIT's total time and its statistics. A run without an exit
record, or with a non-zero exit, is listed as not counted; its report never enters the merge.
"""
from pathlib import Path
import re

HERE = Path(__file__).resolve().parent
RUNS = ['152-session8-pit-unit-full'] + sorted(
    path.name[:-len('.command')] for path in HERE.glob('[12][0-9][0-9]-session10-pit-*.command'))


def first(pattern, text, default='-'):
    match = re.search(pattern, text, re.MULTILINE)
    return match.group(1) if match else default


counted = 0
for name in RUNS:
    log = (HERE / (name + '.log')).read_text(errors='replace')
    exit_file = HERE / (name + '.exit')
    status = exit_file.read_text().strip() if exit_file.exists() else 'no exit record'
    xml = (HERE / 'pit/session8' / (name + '-mutations.xml')).exists() or name.startswith('152')
    ok = status == 'exit 0' and xml
    counted += ok
    wait = first(r'got the host lock after (.+)$', log, '0')
    total = first(r'^> Total  : (.+)$', log)
    stats = first(r'^>> (Generated \d+ mutations Killed \d+ \(\d+%\))$', log)
    uncovered = first(r'^>> Mutations with no coverage (\d+)', log)
    print(f'{name}: {status}; lock wait {wait}; PIT total {total}; {stats}; no coverage {uncovered}; '
          + ('counted' if ok else 'NOT counted'))
print(f'{counted} of {len(RUNS)} runs counted')
