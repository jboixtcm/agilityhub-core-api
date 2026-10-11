#!/usr/bin/env python3
"""Summarize the completed clean verification, including each changed test class."""
from pathlib import Path
import xml.etree.ElementTree as ET

changed = {'E10ContractIT', 'E10ResponseContractTest', 'OpenApiSnapshotTest', 'ListFieldsContractIT'}
seen = set()
for kind in ('surefire', 'failsafe'):
    totals = dict.fromkeys(('tests', 'failures', 'errors', 'skipped'), 0)
    rows = []
    for path in sorted(Path('target', kind + '-reports').glob('TEST-*.xml')):
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
