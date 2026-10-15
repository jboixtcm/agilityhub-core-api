#!/usr/bin/env python3
"""Test totals of the session-8 clean verification (145), read from target/*-reports."""
from pathlib import Path
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
print('Clean verification 145: ' + (HERE / '145-session8-clean-verify.exit').read_text().strip())
wanted = {'ArchitectureTest', 'SentryCaptureTest', 'LogPrivacyTest', 'RateLimitIT',
          'AnonymousRateLimitsTest', 'SecurityInventoryIT', 'E8ContractIT', 'HealthControllerTest',
          'CapturedPaymentInvariantsIT', 'E2ContractIT'}
problems = []
for kind in ('surefire', 'failsafe'):
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    reports = sorted((ROOT / f'target/{kind}-reports').glob('TEST-*.xml'))
    assert reports, 'Missing ' + kind + ' reports'
    selected = []
    for report in reports:
        suite = ET.parse(report).getroot()
        name = suite.get('name').rsplit('.', 1)[-1]
        counts = {key: int(suite.get(key, 0)) for key in totals}
        for key in totals:
            totals[key] += counts[key]
        if name in wanted or name.endswith(('SurvivorsTest', 'Survivors2Test')):
            selected.append(f'{name}: ' + ', '.join(f'{key}={value}' for key, value in counts.items()))
        for case in suite.findall('testcase'):
            if case.find('failure') is not None or case.find('error') is not None:
                problems.append(name + '.' + case.get('name'))
    print(f'{kind}: {len(reports)} classes; ' + ', '.join(f'{key}={value}' for key, value in totals.items()))
    for row in selected:
        print('  ' + row)
for problem in problems:
    print('FAILED ' + problem)
jacoco = ROOT / 'target/site/jacoco/jacoco.xml'
print('JaCoCo report present: ' + str(jacoco.exists()))
assert not problems
print('PASS: no failure or error in any surefire/failsafe report.')
