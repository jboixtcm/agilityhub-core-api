#!/usr/bin/env python3
"""Reproduce the three review findings with the final regressions, restoring every source byte."""
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[3]
prefix = 'src/main/java/com/agilityhub/core/payments/'
paths = [prefix + name for name in (
    'persistence/PaymentOperationRepository.java', 'application/CardPayments.java',
    'application/PaymentRefunds.java', 'application/stripe/StripeCalls.java',
    'application/PaymentNotSubmitted.java')]
saved = {path: (root / path).read_bytes() for path in paths}
try:
    for path in paths:
        (root / path).write_bytes(subprocess.check_output(['git', 'show', '81af180:' + path]))
    # Keep the realistic expiring-key/lost-response fake and final tests while restoring the reviewed production code.
    selection = ('CardPaymentsIT#*expiredProviderKeyNeverReplaysLostCapture+'
                 '*lostRefundReconciliationReleasesExactRemainder+'
                 '*rateLimitedRefundReleasesOnlyDefinitiveReservation')
    result = subprocess.run(['./mvnw', '-q', '-Dit.test=' + selection,
                             'test-compile', 'failsafe:integration-test', 'failsafe:verify'], timeout=600)
    print('Counterfactual Maven exit:', result.returncode, flush=True)
    assert result.returncode != 0
    suite = ET.parse(root / 'target/failsafe-reports/TEST-com.agilityhub.core.payments.api.CardPaymentsIT.xml').getroot()
    failures = [case.get('name') for case in suite.findall('testcase') if case.find('failure') is not None]
    assert int(suite.get('errors')) == 0
    for point in range(1, 4):
        names = [name for name in failures if f'round3_point{point}_' in name]
        assert names, f'No assertion failure for point {point}'
        for name in names:
            print('Observed failing regression:', name, flush=True)
finally:
    for path, content in saved.items():
        (root / path).write_bytes(content)
    assert all((root / path).read_bytes() == content for path, content in saved.items())
    print('All working-tree source bytes restored.', flush=True)
