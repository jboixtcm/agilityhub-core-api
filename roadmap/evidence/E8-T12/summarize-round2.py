#!/usr/bin/env python3
"""Summarize both clean-verification lanes and every class changed in round 2."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
root = Path(__file__).resolve().parents[3]
metrics = ('tests', 'failures', 'errors', 'skipped')
summaries = []
for lane in ('surefire', 'failsafe'):
    reports = sorted((root / ('target/' + lane + '-reports')).glob('TEST-*.xml'))
    assert reports, lane
    totals = dict.fromkeys(metrics, 0)
    for path in reports:
        suite = ET.parse(path).getroot()
        for key in metrics:
            totals[key] += int(suite.get(key, '0'))
    summaries.append(lane + ': ' + ', '.join(key + '=' + str(totals[key]) for key in metrics))
    assert totals['failures'] == totals['errors'] == totals['skipped'] == 0
path = root / 'target/failsafe-reports/TEST-com.agilityhub.core.payments.api.CapturedPaymentInvariantsIT.xml'
suite = ET.parse(path).getroot()
for case in suite.iter('testcase'):
    if 'e8t12' in case.get('name') or 'generatedSequences' in case.get('name'):
        print('  ' + case.get('name') + ': passed')
for output in suite.iter('system-out'):
    for line in (output.text or '').splitlines():
        if line.startswith('E8-T11,') or line.startswith('E8-T12 expired') or line.startswith('E8-T12 round 2'):
            print(line)
for lane, name in (
    ('surefire', 'com.agilityhub.core.payments.application.PaymentRefundsSurvivorsTest'),
    ('surefire', 'com.agilityhub.core.payments.application.BillingRunServiceSurvivorsTest'),
    ('failsafe', 'com.agilityhub.core.configuration.SecurityInventoryIT'),
    ('failsafe', 'com.agilityhub.core.payments.api.CapturedPaymentInvariantsIT'),
):
    changed = ET.parse(root / f'target/{lane}-reports/TEST-{name}.xml').getroot()
    summaries.append(name + ': ' + ', '.join(key + '=' + changed.get(key) for key in metrics))
print('\n'.join(summaries))
verification_exit = (Path(__file__).parent / ((sys.argv[1] if len(sys.argv) > 1 else '06-clean-verify') + '.exit')).read_text().strip()
print('Verification exit: ' + verification_exit)
assert verification_exit == '0', 'The clean verification must have passed, including its post-test gates'
