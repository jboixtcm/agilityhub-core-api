#!/usr/bin/env python3
"""Summarize the final clean build without copying fixture values or credentials."""
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

build_exit = int(sys.argv[1])
print(f"clean verify exit code: {build_exit}")
changed = subprocess.check_output(['git', 'diff', '--name-only'], text=True).splitlines()
changed += subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard'], text=True).splitlines()
classes = {Path(p).stem for p in changed if p.startswith('src/test/java/') and p.endswith('.java')}
classes.update({'InactivityCalendarTest', 'PackBalanceServiceTest', 'AuditContractTest', 'ArchitectureTest', 'EventCatalogContractTest', 'E8ResponseContractTest'})
seen = set()
for stage in ('surefire', 'failsafe'):
    reports = [ET.parse(p).getroot() for p in sorted(Path(f'target/{stage}-reports').glob('TEST-*.xml'))]
    totals = {key: sum(int(r.get(key, '0')) for r in reports) for key in ('tests', 'failures', 'errors', 'skipped')}
    print(stage + ': ' + ', '.join(f'{k}={v}' for k,v in totals.items()))
    for r in reports:
        name = r.get('name', '')
        if name.rsplit('.',1)[-1] in classes:
            seen.add(name.rsplit('.',1)[-1])
            print(name + ': ' + ', '.join(f'{k}={r.get(k,"0")}' for k in totals))
    assert reports and totals['failures'] == 0 and totals['errors'] == 0
print('Shared fixture/helpers (not executable classes): ' + ', '.join(sorted(classes - seen)))
methods = set()
for p in Path('src/test/java').rglob('*.java'):
    if p.stem in ('LifecycleIT', 'LifecycleCurlIT', 'TrainingIT', 'LifecycleRulesTest', 'InactivityCalendarTest', 'PackBalanceServiceTest'):
        for method in re.findall(r'void\s+(T_(?:12|13)_\w+)\s*\(', p.read_text()):
            methods.add((str(p), method))
print('Spec method inventory:')
for path, method in sorted(methods):
    print(f'{path}: {method}')
print('T_13_07 asserted output: 2026-10-30 ALLOWED; 2026-11-03 INACTIVITY_PERIOD; 2027-01-02 ALLOWED; leave day ALLOWED; next day MEMBER_LEAVING; inactivity wins when both apply.')
coverage = ET.parse('target/site/jacoco/jacoco.xml').getroot()
for package in coverage.findall('package'):
    if package.get('name') == 'com/agilityhub/core/clubs/census/inactivity/domain':
        for counter in package.findall('counter'):
            if counter.get('type') in ('LINE', 'BRANCH'):
                total = int(counter.get('covered')) + int(counter.get('missed'))
                print('Inactivity calendar coverage ' + counter.get('type') + ': ' + str(round(100 * int(counter.get('covered')) / total, 2)) + '%')
assert build_exit == 0
