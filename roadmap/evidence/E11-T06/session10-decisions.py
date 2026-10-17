#!/usr/bin/env python3
"""Join the session-10 triage decisions (pit/session10/decisions-*.json) into one file for session6-triage.py.

Each decision file must cover exactly its slice (`slice-*.json`, or `identity-not-killed.json` for identity); an
identity decided twice is refused. Usage: session10-decisions.py OUTPUT.json
"""
import json
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent / 'pit/session10'
SLICES = {'decisions-payments-refunds.json': 'slice-payments-refunds.json',
          'decisions-payments-other.json': 'slice-payments-other.json',
          'decisions-bookings-b0-b3.json': 'slice-bookings-b0-b3.json',
          'decisions-bookings-b4-b5.json': 'slice-bookings-b4-b5.json',
          'decisions-identity.json': 'identity-not-killed.json'}
joined = {}
for decisions, rows in SLICES.items():
    decided = json.loads((HERE / decisions).read_text())
    expected = {row['identity'] for row in json.loads((HERE / rows).read_text())}
    assert set(decided) == expected, f'{decisions} does not cover exactly {rows}'
    assert all(value.startswith(('test:', 'reason:')) for value in decided.values()), decisions
    assert not set(decided) & set(joined), f'{decisions} repeats an identity'
    joined.update(decided)
    print(f'{decisions}: {len(decided)} rows, {sum(v.startswith("test:") for v in decided.values())} tests')
output = Path(sys.argv[1])
with output.open('x') as handle:
    json.dump(joined, handle, indent=1, ensure_ascii=False)
    handle.write('\n')
print(f'{len(joined)} decisions; {sum(v.startswith("test:") for v in joined.values())} tests, '
      f'{sum(v.startswith("reason:") for v in joined.values())} reasons')
