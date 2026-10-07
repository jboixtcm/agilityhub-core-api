#!/usr/bin/env python3
"""Prove the reversal assertions independently of event recognition, restoring source in finally."""
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
p = Path('src/main/java/com/agilityhub/core/payments/application/PaymentRefunds.java')
original = p.read_bytes()
try:
    body = original.decode()
    needle = '            reverse(intent, refundId, operation, status);'
    assert needle in body
    p.write_text(body.replace(needle, '            // Counterfactual: reversal disabled.'))
    command = ['./mvnw', '-q', '-DskipTests', 'test-compile', 'failsafe:integration-test', 'failsafe:verify',
               '-Dit.test=CardPaymentsIT#*round3*Refund*+*round3*refundLifecycle*', '-DskipTests=false']
    print('Counterfactual command: ' + ' '.join(command), flush=True)
    result = subprocess.run(command)
    report = ET.parse('target/failsafe-reports/TEST-com.agilityhub.core.payments.api.CardPaymentsIT.xml').getroot()
    failures = [case.attrib['name'] for case in report.findall('testcase') if case.find('failure') is not None]
    print('Counterfactual Maven exit:', result.returncode)
    for name in failures:
        print('FAILED WITH REVERSAL REMOVED:', name)
    for name in ('refundLifecycleReversesInvoice', 'cancelledRefundRestoresOnly', 'failedLateRefundReverses'):
        assert any(name in failure for failure in failures), name
    assert result.returncode == 1 and report.attrib['errors'] == '0'
finally:
    p.write_bytes(original)
    print('Restored PaymentRefunds byte for byte.', flush=True)
