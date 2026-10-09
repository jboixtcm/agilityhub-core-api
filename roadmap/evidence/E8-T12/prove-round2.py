#!/usr/bin/env python3
"""Reproduce E103 against the reviewed implementation, restoring all source bytes without git writes."""
from pathlib import Path
import shlex
import subprocess
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[3]
source = root / 'src/main/java/com/agilityhub/core/payments/application/PaymentRefunds.java'
saved = source.read_bytes()
baseline = subprocess.check_output(['git', 'show', 'f1a8e5d:' + str(source.relative_to(root))], cwd=root)
args = ['./mvnw', '-q', '-Dit.test=CapturedPaymentInvariantsIT#T_12_17_e8t12_round2*',
        'test-compile', 'failsafe:integration-test', 'failsafe:verify']
try:
    source.write_bytes(baseline)
    print('Reviewed PaymentRefunds: f1a8e5d; final regression tests and supporting repositories retained.', flush=True)
    print('Command: ' + shlex.join(args), flush=True)
    result = subprocess.run(args, cwd=root, timeout=1800)
    print('Maven exit:', result.returncode, flush=True)
    assert result.returncode == 1
    suite = ET.parse(root / 'target/failsafe-reports/TEST-com.agilityhub.core.payments.api.CapturedPaymentInvariantsIT.xml').getroot()
    assert int(suite.get('errors')) == int(suite.get('skipped')) == 0
    for name in ('point1_sharedCaptureHasOneAllowance', 'point1_concurrentRowsRecheckTheCaptureAfterCommit',
                 'point1_sharedSequencesConserveTheIntent', 'point1_sharedCreditsAbsorbBeforeABilledCreditNeedsIntervention',
                 'point2_lateInterventionSubtractsDashboardRepayments', 'point2_generatedLateRepaymentsKeepTheBalanceCurrent',
                 'point2_lateRepaymentDoesNotSpendAnotherCapture'):
        cases = [case for case in suite.iter('testcase') if name in case.get('name')]
        assert cases and all(case.find('failure') is not None for case in cases), name
        print(name + ': ' + str(len(cases)) + ' assertion failures', flush=True)
    credit_cases = [case for case in suite.iter('testcase') if 'point1_dashboardUsesUncreditedCapture' in case.get('name')]
    assert len(credit_cases) == 6 and sum(case.find('failure') is not None for case in credit_cases) == 4
    print('point1_dashboardUsesUncreditedCapture: 4 assertion failures; 2 full-refund controls pass', flush=True)
    print('Totals: ' + ', '.join(key + '=' + suite.get(key) for key in ('tests', 'failures', 'errors', 'skipped')), flush=True)
finally:
    source.write_bytes(saved)
    assert source.read_bytes() == saved
    print('Final PaymentRefunds restored byte for byte.', flush=True)
