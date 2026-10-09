#!/usr/bin/env python3
"""Reproduce E102 on the reviewed PaymentRefunds with this task's final regression tests; no git writes."""
from pathlib import Path
import shlex
import subprocess
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[3]
source = root / 'src/main/java/com/agilityhub/core/payments/application/PaymentRefunds.java'
saved = source.read_bytes()
baseline = subprocess.check_output(['git', 'show', '867dfc0:' + str(source.relative_to(root))], cwd=root)
args = ['./mvnw', '-q', '-Dit.test=CapturedPaymentInvariantsIT#T_12_17_e8t12*', 'test-compile', 'failsafe:integration-test', 'failsafe:verify']
try:
    source.write_bytes(baseline)
    print('Reviewed PaymentRefunds: 867dfc0; final tests and supporting repositories retained.', flush=True)
    print('Command: ' + shlex.join(args), flush=True)
    result = subprocess.run(args, cwd=root, timeout=1800)
    print('Maven exit:', result.returncode, flush=True)
    assert result.returncode == 1
    suite = ET.parse(root / 'target/failsafe-reports/TEST-com.agilityhub.core.payments.api.CapturedPaymentInvariantsIT.xml').getroot()
    assert int(suite.get('errors')) == int(suite.get('skipped')) == 0
    required = ('point1_pendingDashboardRefundIsReservedBeforeBilling', 'point1_pendingDashboardReducesNewAdminReservations',
                'point2_expiredCheckoutFailureNotifiesTheAdmin', 'point2_generatedExpiredCheckoutSequences')
    cases = list(suite.iter('testcase'))
    for name in required:
        matches = [case for case in cases if name in case.get('name')]
        assert matches and all(case.find('failure') is not None for case in matches), name
        print(name + ': ' + str(len(matches)) + ' assertion failures', flush=True)
    print('Totals: ' + ', '.join(key + '=' + suite.get(key) for key in ('tests', 'failures', 'errors', 'skipped')), flush=True)
finally:
    source.write_bytes(saved)
    assert source.read_bytes() == saved
    print('Final PaymentRefunds restored byte for byte.', flush=True)
