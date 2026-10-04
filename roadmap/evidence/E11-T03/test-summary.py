#!/usr/bin/env python3
"""Print clean verification counts without overwriting an earlier summary."""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
NAMES = {'SecurityInventoryIT', 'SecurityHardeningIT', 'AnonymousRateLimitsTest', 'RequestTraceFilterTest',
         'LogPrivacyTest', 'MongoTimeoutConfigurationTest', 'CorsIT', 'ClubCorsConfigurationSourceTest',
         'HealthIndependenceIT', 'ActivityIT', 'SignupSecurityFixesIT', 'WaitlistIT', 'IdentityCoreIT',
         'MongoRequestTimeoutIT', 'DemoScenarioSeedIT', 'TrainingIT'}
for directory in ('surefire-reports', 'failsafe-reports'):
    reports = sorted((ROOT / 'target' / directory).glob('TEST-*.xml'))
    assert reports, f'Missing {directory}'
    totals = dict.fromkeys(('tests', 'failures', 'errors', 'skipped'), 0)
    for path in reports:
        suite = ET.parse(path).getroot()
        counts = {key: int(suite.get(key, '0')) for key in totals}
        for key, value in counts.items():
            totals[key] += value
        if suite.get('name', '').rsplit('.', 1)[-1] in NAMES:
            print(suite.get('name'), ' '.join(f'{key}={value}' for key, value in counts.items()))
    print(directory, 'TOTAL', ' '.join(f'{key}={value}' for key, value in totals.items()))
    assert totals['failures'] == totals['errors'] == 0
