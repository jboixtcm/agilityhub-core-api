#!/usr/bin/env python3
"""E8-T11: totals of the clean run's reports, the classes this task adds or changes, and the generator's reach."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[3]
metrics = ('tests', 'failures', 'errors', 'skipped')
suites = {}
for lane in ('surefire', 'failsafe'):
    total = dict.fromkeys(metrics, 0)
    files = sorted((root / f'target/{lane}-reports').glob('TEST-*.xml'))
    assert files, f'Missing {lane} reports'
    for path in files:
        suite = ET.parse(path).getroot()
        counts = {key: int(suite.get(key, '0')) for key in metrics}
        suites[suite.get('name')] = (counts, suite)
        for key in metrics:
            total[key] += counts[key]
    print(lane + ': ' + ', '.join(f'{key}={total[key]}' for key in metrics))
    assert total['failures'] == total['errors'] == total['skipped'] == 0

print('\nE8-T11 test classes (added or changed), with their E8-T11 cases:')
for name in ('com.agilityhub.core.payments.application.stripe.StripeCallsTest',
             'com.agilityhub.core.payments.api.CapturedPaymentInvariantsIT',
             'com.agilityhub.core.payments.api.CardPaymentsIT'):
    counts, suite = suites[name]
    print(name + ': ' + ', '.join(f'{key}={counts[key]}' for key in metrics))
    for case in suite.iter('testcase'):
        if any(marker in case.get('name') for marker in ('e8t11', 'round5_generated', 'round4_point2_rejectedContribution')):
            print('  ' + case.get('name') + ' ' + case.get('time') + 's')

report = (root / 'target/failsafe-reports/TEST-com.agilityhub.core.payments.api.CapturedPaymentInvariantsIT.xml').read_text()
reach = re.findall(r'E8-T11, [^\n]*steps reached: \{[^}]*\}', report)
assert reach, 'generator reach missing'
print('\nGenerator reach: ' + reach[-1])
