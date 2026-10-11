#!/usr/bin/env python3
"""Summarize the clean run and all round-4 regressions, requiring every gate to pass."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
root = Path(sys.argv[2]).resolve() if len(sys.argv) > 2 else Path(__file__).resolve().parents[3]
metrics = ('tests', 'failures', 'errors', 'skipped')
for lane in ('surefire', 'failsafe'):
    reports = sorted((root / ('target/' + lane + '-reports')).glob('TEST-*.xml'))
    assert reports, lane
    totals = dict.fromkeys(metrics, 0)
    for path in reports:
        suite = ET.parse(path).getroot()
        for key in metrics:
            totals[key] += int(suite.get(key, '0'))
    print(lane + ': ' + ', '.join(key + '=' + str(totals[key]) for key in metrics))
    assert totals['failures'] == totals['errors'] == totals['skipped'] == 0
name = 'com.agilityhub.core.payments.domain.PaymentIntentLedgerTest'
suite = ET.parse(root / f'target/surefire-reports/TEST-{name}.xml').getroot()
print(name + ': ' + ', '.join(key + '=' + suite.get(key) for key in metrics))
name = 'com.agilityhub.core.payments.api.CapturedPaymentInvariantsIT'
suite = ET.parse(root / f'target/failsafe-reports/TEST-{name}.xml').getroot()
print(name + ': ' + ', '.join(key + '=' + suite.get(key) for key in metrics))
for case in suite.iter('testcase'):
    if 'round4' in case.get('name'):
        print(case.get('name') + ': passed')
for output in suite.iter('system-out'):
    for line in (output.text or '').splitlines():
        if line.startswith('E8-T12 shared generator'):
            print(line)
code = (Path(__file__).parent / (sys.argv[1] + '.exit')).read_text().strip()
print('Verification exit: ' + code)
assert code == '0'
