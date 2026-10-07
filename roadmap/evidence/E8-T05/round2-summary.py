#!/usr/bin/env python3
"""Read clean Maven XML totals and retain the S12/S13 test inventory without test payloads."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[3]
run, code = sys.argv[1:]
assert code == '0', 'A failed build is not final evidence'
fields = ('tests', 'failures', 'errors', 'skipped')
selected = {'PackBalanceServiceTest', 'LifecycleIT', 'LifecycleConcurrencyIT', 'ClubDefinitionsIT',
            'ArchitectureTest', 'AuditContractTest', 'EventCatalogContractTest', 'MessageParityTest',
            'E8ContractIT', 'InactivityCalendarTest', 'LifecycleRulesTest', 'BookingsIT', 'WaitlistIT', 'TrainingIT', 'DemoPlanningSeedIT'}
inventory = []
print(f'Clean verification log: {run}; exit={code}')
for suite in ('surefire', 'failsafe'):
    rows = [ET.parse(p).getroot() for p in sorted((root / f'target/{suite}-reports').glob('TEST-*.xml'))]
    assert rows, f'Missing {suite} reports'
    totals = {f: sum(int(r.attrib.get(f, 0)) for r in rows) for f in fields}
    print(suite + ': ' + ', '.join(f'{f}={totals[f]}' for f in fields))
    assert sum(totals[f] for f in fields[1:]) == 0, f'{suite} is not green'
    for row in rows:
        name = row.attrib['name'].rsplit('.', 1)[-1]
        if name in selected:
            print(f'  {name}: ' + ', '.join(f'{f}={row.attrib.get(f, 0)}' for f in fields))
            for test in row.findall('testcase'):
                if test.attrib['name'].startswith(('T_13_', 'T_12_07', 'T_12_22', 'T_12_31')):
                    inventory.append(name + '.' + test.attrib['name'])
(root / 'roadmap/evidence/E8-T05/round2-test-inventory.txt').write_text('\n'.join(inventory)+'\n')
print(f'S12/S13 method inventory: round2-test-inventory.txt ({len(inventory)} invocations)')
