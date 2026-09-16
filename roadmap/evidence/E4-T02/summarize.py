#!/usr/bin/env python3
"""Summarize the fresh Maven verification reports without logging secrets."""
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[3]
for directory, label in [('surefire-reports', 'Unit/contract'), ('failsafe-reports', 'Integration')]:
    totals = dict.fromkeys(['tests', 'failures', 'errors', 'skipped'], 0)
    for path in (root / 'target' / directory).glob('TEST-*.xml'):
        suite = ET.parse(path).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, 0))
    print(label + ': ' + ', '.join(f'{key}={value}' for key, value in totals.items()))
    assert totals['tests'] and not any(totals[key] for key in ['failures', 'errors', 'skipped'])
coverage = ET.parse(root / 'target/site/jacoco/jacoco.xml').getroot()
for package in coverage.findall('package'):
    name = package.get('name')
    if name.startswith('com/agilityhub/core/clubs/scheduling/'):
        counts = {counter.get('type'): (int(counter.get('covered')), int(counter.get('missed'))) for counter in package.findall('counter')}
        values = []
        for key in ['LINE', 'BRANCH']:
            covered, missed = counts.get(key, (0, 0))
            percent = 100 * covered / (covered + missed) if covered + missed else 100
            values.append(f'{key}={covered}/{covered + missed} ({percent:.2f}%)')
        print(name + ': ' + ', '.join(values))
print('S06 P2 test methods:')
paths = sorted((root / 'src/test/java/com/agilityhub/core/clubs/scheduling').rglob('*.java'))
paths.append(root / 'src/test/java/com/agilityhub/core/configuration/E4ContractIT.java')
for path in paths:
    import re
    for method in re.findall(r'void (T_06_[A-Za-z0-9_]+)\(', path.read_text()):
        print(path.stem + '.' + method)
