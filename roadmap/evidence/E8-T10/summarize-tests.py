#!/usr/bin/env python3
"""Summarize the fresh Maven XML reports, with every Java test class changed by E8-T10."""
from pathlib import Path
import xml.etree.ElementTree as ET

changed = {'PlayoffMigrationIT', 'E8ScheduledProcessesIT'}
found = set()
for suite in ('surefire', 'failsafe'):
    paths = list(Path('target', suite + '-reports').glob('TEST-*.xml'))
    assert paths, suite + ' reports missing'
    totals = dict.fromkeys(('tests', 'failures', 'errors', 'skipped'), 0)
    rows = []
    for path in sorted(paths):
        root = ET.parse(path).getroot()
        counts = {name: int(root.get(name, 0)) for name in totals}
        for name, value in counts.items():
            totals[name] += value
        name = root.get('name').split('.')[-1]
        if name in changed:
            found.add(name)
            rows.append('  ' + name + ': ' + ' '.join(f'{k}={v}' for k, v in counts.items()))
            for case in root.findall('testcase'):
                if 'point' in case.get('name'):
                    assert case.find('failure') is None and case.find('error') is None
                    rows.append('    PASS ' + case.get('name'))
    print(suite + ': ' + ' '.join(f'{k}={v}' for k, v in totals.items()))
    print('\n'.join(rows))
    assert totals['failures'] == totals['errors'] == totals['skipped'] == 0
assert found == changed
