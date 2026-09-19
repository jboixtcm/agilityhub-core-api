#!/usr/bin/env python3
"""Summarize Maven XML reports and enforced JaCoCo package coverage."""
from pathlib import Path
import xml.etree.ElementTree as ET
root = Path(__file__).resolve().parents[3]
for kind in ('surefire','failsafe'):
    # The explicit snapshot command also leaves a Surefire copy of a Failsafe suite.
    paths = (root/'target'/f'{kind}-reports').glob('TEST-*.xml')
    reports = [ET.parse(p).getroot() for p in paths if kind != 'surefire' or not (root/'target/failsafe-reports'/p.name).exists()]
    counts = {key:sum(int(r.get(key,0)) for r in reports) for key in ('tests','failures','errors','skipped')}
    print(f'{kind}: {counts}')
    assert counts['failures'] == counts['errors'] == 0
print('T-06 methods in CalendarRulesTest/CalendarIT:')
for kind in ('surefire','failsafe'):
    for path in sorted((root/'target'/f'{kind}-reports').glob('TEST-*Calendar*.xml')):
        for test in ET.parse(path).getroot().findall('testcase'):
            print('  '+test.get('name'))
report = ET.parse(root/'target/site/jacoco/jacoco.xml').getroot()
for package in report.findall('package'):
    name = package.get('name').replace('/','.')
    limits = {'LINE':0.85, 'BRANCH':0.80} if '.application' in name or '.domain' in name else {'LINE':0.70} if '.api' in name else {}
    for kind,limit in limits.items():
        counter = package.find(f"counter[@type='{kind}']")
        if counter is None: continue
        missed,covered = int(counter.get('missed')),int(counter.get('covered'))
        ratio = covered/(covered+missed) if covered+missed else 1
        assert ratio >= limit, f'{name} {kind}: {ratio}'
        if '.scheduling.' in name: print(f'{name}: {kind} {covered}/{covered+missed} ({ratio:.2%})')
assert (root/'docs/openapi/openapi.json').read_bytes() == (root/'target/openapi.json').read_bytes()
print('PASS all coverage gates; generated OpenAPI byte-identical to reviewed snapshot')
