#!/usr/bin/env python3
"""Run the new regressions against the pre-fix implementations, restoring all working files in finally."""
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

paths = [Path(p) for p in subprocess.check_output(['git', 'diff', '--name-only', '--', 'src/main/java'], text=True).splitlines()
         if not p.endswith('FakePaymentProvider.java')]
originals = {p: p.read_bytes() for p in paths}
try:
    for p in paths:
        p.write_bytes(subprocess.check_output(['git', 'show', 'HEAD:' + str(p)]))
    command = ['./mvnw', '-q', '-DskipTests', 'test-compile', 'failsafe:integration-test', 'failsafe:verify',
               '-Dit.test=CardPaymentsIT#*round3*', '-DskipTests=false']
    print('Counterfactual command: ' + ' '.join(command), flush=True)
    result = subprocess.run(command)
    report = ET.parse('target/failsafe-reports/TEST-com.agilityhub.core.payments.api.CardPaymentsIT.xml').getroot()
    failures = [case.attrib['name'] for case in report.findall('testcase') if case.find('failure') is not None]
    print('Counterfactual Maven exit:', result.returncode)
    for name in failures:
        print('FAILED BEFORE FIX:', name)
    for name in ('chargeWithoutEmbeddedRefunds', 'refundLifecycleReversesInvoice', 'cancelledRefundRestoresOnly',
                 'cancellationBeforeConfirmation', 'exhaustedChargeResolvesOnly', 'lateSignupReusesRows'):
        assert any(name in failure for failure in failures), name
    assert result.returncode == 1 and report.attrib['errors'] == '0'
finally:
    for p, body in originals.items():
        p.write_bytes(body)
    print('Restored all implementation files byte for byte.', flush=True)
