#!/usr/bin/env python3
"""Summarize the completed clean verification, including each changed test class."""
from pathlib import Path
import xml.etree.ElementTree as ET
import sys

target = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("target")
if len(sys.argv) > 2:
    exit_code = int(Path(sys.argv[2]).read_text())
    print(f"Clean verification exit: {exit_code}")
    assert exit_code == 0, "Clean verification did not pass"

changed = {'E10ContractIT', 'E10ResponseContractTest', 'OpenApiSnapshotTest', 'ListFieldsContractIT', 'E5BackOfficeContractTest'}
seen = set()
for kind in ('surefire', 'failsafe'):
    totals = dict.fromkeys(('tests', 'failures', 'errors', 'skipped'), 0)
    rows = []
    for path in sorted((target / (kind + '-reports')).glob('TEST-*.xml')):
        root = ET.parse(path).getroot()
        values = {key: int(root.get(key, '0')) for key in totals}
        for key, value in values.items():
            totals[key] += value
        name = root.get('name', '').rsplit('.', 1)[-1]
        if name in changed:
            seen.add(name)
            rows.append('  ' + name + ': ' + ' '.join(f'{key}={value}' for key, value in values.items()))
    print(kind + ' totals: ' + ' '.join(f'{key}={value}' for key, value in totals.items()))
    print('\n'.join(rows))
    assert totals['tests'] > 0, kind + ': no report'
    assert totals['failures'] == totals['errors'] == totals['skipped'] == 0, kind + ': not completely green'
assert seen == changed, 'Missing changed classes: ' + str(changed - seen)
