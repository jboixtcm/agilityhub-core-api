#!/usr/bin/env python3
"""Summarize the final Maven test/coverage artifacts without printing credentials."""
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[3]
for suite in ('surefire', 'failsafe'):
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    paths = list((root / 'target' / (suite + '-reports')).glob('TEST-*.xml'))
    assert paths, suite + ' reports missing'
    for path in paths:
        report = ET.parse(path).getroot()
        for name in totals:
            totals[name] += int(report.get(name, 0))
    print(suite + ': ' + ', '.join(f'{name}={value}' for name, value in totals.items()))
    assert totals['failures'] == totals['errors'] == totals['skipped'] == 0

report = ET.parse(root / 'target/site/jacoco/jacoco.xml').getroot()
for package in report.findall('package'):
    if '/dashboard' not in package.get('name'):
        continue
    counts = {row.get('type'): (int(row.get('covered')), int(row.get('missed'))) for row in package.findall('counter')}
    values = []
    for metric in ('LINE', 'BRANCH'):
        covered, missed = counts.get(metric, (0, 0))
        values.append(f'{metric.lower()}={covered}/{covered + missed} ({100 * covered / (covered + missed):.2f}%)' if covered + missed else metric.lower() + '=n/a')
    print(package.get('name').replace('/', '.') + ': ' + ', '.join(values))

for suite in ('surefire', 'failsafe'):
    for path in sorted((root / 'target' / (suite + '-reports')).glob('TEST-*dashboard*.xml')):
        for test in ET.parse(path).getroot().findall('testcase'):
            print(test.get('name'))
print('OpenAPI build artifact matches committed snapshot:', (root / 'target/openapi.json').read_bytes() == (root / 'docs/openapi/openapi.json').read_bytes())
assert (root / 'target/openapi.json').read_bytes() == (root / 'docs/openapi/openapi.json').read_bytes()
