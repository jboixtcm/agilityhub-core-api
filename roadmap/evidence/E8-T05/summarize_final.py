#!/usr/bin/env python3
"""Summarize the final clean build from the XML reports: totals, one line per task test class, T-12/T-13 inventory."""
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

print(f"clean verify (log {sys.argv[1]}) exit code: {sys.argv[2]}")
changed = subprocess.check_output(['git', 'diff', '--name-only', '2e31d8a', '--', 'src/test/java'], text=True).splitlines()
changed += subprocess.check_output(['git', 'diff', '--name-only', '--', 'src/test/java'], text=True).splitlines()
classes = {Path(p).stem for p in changed if p.endswith('.java')}
classes.update({'ArchitectureTest', 'AuditContractTest', 'EventCatalogContractTest', 'MessageParityTest',
                'ErrorCatalogContractTest', 'WaitlistIT', 'DemoPlanningSeedIT'})
seen = set()
for stage in ('surefire', 'failsafe'):
    reports = [ET.parse(p).getroot() for p in sorted(Path(f'target/{stage}-reports').glob('TEST-*.xml'))]
    totals = {k: sum(int(r.get(k, '0')) for r in reports) for k in ('tests', 'failures', 'errors', 'skipped')}
    print(stage + ': ' + ', '.join(f'{k}={v}' for k, v in totals.items()))
    for r in reports:
        short = r.get('name').rsplit('.', 1)[-1]
        if short in classes:
            seen.add(short)
            print('  ' + r.get('name') + ': ' + ', '.join(f'{k}={r.get(k, "0")}' for k in totals))
print('Changed test sources that are fixtures/helpers, not executable classes: ' + ', '.join(sorted(classes - seen)))
print('Spec method inventory (T-12 / T-13):')
for p in sorted(Path('src/test/java').rglob('*.java')):
    if p.stem in ('LifecycleIT', 'LifecycleCurlIT', 'TrainingIT', 'LifecycleRulesTest', 'InactivityCalendarTest', 'PackBalanceServiceTest'):
        for m in re.findall(r'void\s+(T_1[23]_\w+)\s*\(', p.read_text()):
            print(f'  {p.stem}.{m}')
coverage = ET.parse('target/site/jacoco/jacoco.xml').getroot()
for package in coverage.findall('package'):
    if package.get('name') == 'com/agilityhub/core/clubs/census/inactivity/domain':
        for counter in package.findall('counter'):
            if counter.get('type') in ('LINE', 'BRANCH'):
                total = int(counter.get('covered')) + int(counter.get('missed'))
                print(f"Inactivity calendar coverage {counter.get('type')}: {round(100 * int(counter.get('covered')) / total, 2)}%")
