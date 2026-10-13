#!/usr/bin/env python3
"""Report the failed full run separately from the focused payment regression result."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

mode, source, attempt = sys.argv[1:]
root = Path(source)
evidence = Path(__file__).resolve().parent
metrics = ('tests', 'failures', 'errors', 'skipped')

def read(lane, name):
    return ET.parse(root / f'target/{lane}-reports/TEST-{name}.xml').getroot()

def show(label, suites):
    totals = {key: sum(int(s.get(key, '0')) for s in suites) for key in metrics}
    print(label + ': ' + ', '.join(f'{key}={totals[key]}' for key in metrics))
    return totals

if mode == 'full':
    suites = [ET.parse(path).getroot() for path in sorted((root / 'target/surefire-reports').glob('TEST-*.xml'))]
    show('Full clean run / surefire', suites)
    print('Full clean run / failsafe: not reached after surefire failure (0 executed).')
    for name in ('PaymentIntentLedgerTest', 'PaymentRefundsSurvivorsTest', 'BillingRunServiceSurvivorsTest', 'StripeWebhooksSurvivorsTest'):
        package = 'domain' if name == 'PaymentIntentLedgerTest' else 'application'
        full_name = f'com.agilityhub.core.payments.{package}.{name}'
        show(full_name, [read('surefire', full_name)])
    for suite in suites:
        for case in suite.findall('testcase'):
            for tag in ('failure', 'error'):
                if case.find(tag) is not None:
                    print(f'{tag}: {suite.get("name")}.{case.get("name")}')
    expected = '1'
else:
    for lane, name in (
        ('surefire', 'com.agilityhub.core.payments.domain.PaymentIntentLedgerTest'),
        ('failsafe', 'com.agilityhub.core.payments.api.CapturedPaymentInvariantsIT'),
    ):
        suite = read(lane, name)
        totals = show('Focused ' + name, [suite])
        assert totals['failures'] == totals['errors'] == totals['skipped'] == 0
        for case in suite.findall('testcase'):
            if 'e8t12_round5' in case.get('name'):
                print(case.get('name') + ': passed')
    expected = '0'
code = (evidence / (attempt + '.exit')).read_text().strip()
print('Maven exit: ' + code)
assert code == expected
