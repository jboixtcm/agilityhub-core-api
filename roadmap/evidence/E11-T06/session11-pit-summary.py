#!/usr/bin/env python3
"""Session 11: the step-8 table of the report, read from the gate's own files (E11-T06).

Per package: generated mutants and assertion kills of the unit pass `152`, of session 10's merge `219` (code of `e94adcb0`) and
of the final gate `237` (current code); the not-killed rows of the final triage `242` by kind; and the `test:` decisions whose
mutant the gate counts as killed.
"""
from collections import Counter
from importlib.machinery import SourceFileLoader
import json
from pathlib import Path
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
TRIAGE = SourceFileLoader('triage', str(HERE / 'session6-triage.py')).load_module()
PACKAGES = {'bookings': 'com.agilityhub.core.clubs.bookings.', 'identity': 'com.agilityhub.core.identity.',
            'payments': 'com.agilityhub.core.payments.'}


def package(clazz):
    return next(name for name, prefix in PACKAGES.items() if clazz.startswith(prefix))


def kills(path):
    generated, killed = Counter(), Counter()
    for row in ET.parse(path).getroot():
        name = package(row.findtext('mutatedClass'))
        generated[name] += 1
        killed[name] += row.get('status') == 'KILLED'
    return generated, killed


def cell(generated, killed, name):
    return f'{killed[name]:,} / {generated[name]:,} ({100 * killed[name] / generated[name]:.2f} %)'


runs = {'unit 152': kills(HERE / 'pit/session8/unit-mutations.xml'),
        'merge 219': kills(HERE / 'pit/session10/merged-mutations.xml'),
        'gate 237': kills(HERE / 'pit/session11/gate-mutations.xml')}
decisions = json.loads((HERE / 'pit/session11/decisions-all-r2.json').read_text())
status = {TRIAGE.identity(row): row for row in ET.parse(HERE / 'pit/session11/gate-mutations.xml').getroot()}
tests = Counter(package(key.split('|')[0]) for key, value in decisions.items()
                if value.startswith('test:') and key in status and status[key].get('status') == 'KILLED')
kinds = Counter()
for row in json.loads((HERE / 'pit/session11/triage-r2.json').read_text()):
    kind = 'reason' if row['reason'].startswith('reason:') else 'generated JAXB' if row['reason'].startswith('Generated') else 'other'
    kinds[package(row['identity'].split('|')[0]), kind, row['status']] += 1
print('| Package | ' + ' | '.join(runs) + ' | killed by a triage test | not killed: reasons (statuses) |')
print('|---|---|---|---|---|---|')
for name in PACKAGES:
    rows = {f'{kind} {state}': count for (pkg, kind, state), count in sorted(kinds.items()) if pkg == name}
    print(f'| `{name}` | ' + ' | '.join(cell(*runs[run], name) for run in runs) + f' | {tests[name]} | '
          + ', '.join(f'{key} {count}' for key, count in rows.items()) + ' |')
print(f'Not-killed rows by kind: {dict(Counter(kind for (_, kind, _) in kinds.elements()))}')
