#!/usr/bin/env python3
"""E8-T11 point 4: run this task's tests against the reviewed production code, from committed files only.

Restores the reviewed versions (commit BASE, the published state the review of E8-T09 round 5 read) of the production files
this task changes, keeps the final tests and the test-only provider hooks, and reverts the generator's point-6 line. Runs the
point tests, prints the failing ones by point, asserts every point fails, and restores every working-tree byte.
Usage (from the repository root): python3 roadmap/evidence/E8-T11/prove-counterfactual.py
"""
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

BASE = 'ca2f1a4'
root = Path(__file__).resolve().parents[3]
prefix = 'src/main/java/com/agilityhub/core/payments/'
restored = [prefix + name for name in (
    'application/PaymentRefunds.java', 'application/PaymentRetryPolicy.java', 'application/PaymentAudits.java',
    'application/stripe/StripeCalls.java', 'persistence/PaymentOperationRepository.java')]
generator = 'src/test/java/com/agilityhub/core/payments/api/CapturedPaymentInvariantsIT.java'
point6_fix, point6_reviewed = 'unused = fake.clearRefundRejections();', 'unused = 0;'
saved = {path: (root / path).read_bytes() for path in restored + [generator]}


def run(arguments):
    print('$ ' + ' '.join(arguments), flush=True)
    result = subprocess.run(arguments, cwd=root, timeout=1800)
    print('Maven exit:', result.returncode, flush=True)
    return result.returncode


def cases(report):
    suite = ET.parse(root / report).getroot()
    print(f"{suite.get('name')}: tests={suite.get('tests')} failures={suite.get('failures')} errors={suite.get('errors')}", flush=True)
    return {case.get('name'): ('failure' if case.find('failure') is not None else 'error' if case.find('error') is not None else 'passed')
            for case in suite.iter('testcase')}


try:
    for path in restored:
        (root / path).write_bytes(subprocess.check_output(['git', 'show', BASE + ':' + path], cwd=root))
    text = (root / generator).read_text()
    assert text.count(point6_fix) == 1, 'point-6 line not found'
    (root / generator).write_text(text.replace(point6_fix, point6_reviewed))
    unit = run(['./mvnw', '-q', '-Dtest=StripeCallsTest#T_12_17_e8t11*', '-Dsurefire.failIfNoSpecifiedTests=false', 'test-compile', 'surefire:test'])
    integration = run(['./mvnw', '-q', '-Dit.test=CapturedPaymentInvariantsIT#T_12_17_e8t11*,CardPaymentsIT#T_12_17_round4_point2_rejectedContribution*',
                       'test-compile', 'failsafe:integration-test', 'failsafe:verify'])
    assert unit != 0 and integration != 0, 'the reviewed code passed'
    results = {}
    results.update(cases('target/surefire-reports/TEST-com.agilityhub.core.payments.application.stripe.StripeCallsTest.xml'))
    results.update(cases('target/failsafe-reports/TEST-com.agilityhub.core.payments.api.CapturedPaymentInvariantsIT.xml'))
    results.update({'CardPaymentsIT.' + name: outcome for name, outcome in
                    cases('target/failsafe-reports/TEST-com.agilityhub.core.payments.api.CardPaymentsIT.xml').items()})
    for name, outcome in sorted(results.items()):
        print(f'  {outcome:7} {name}', flush=True)
    for point, marker in ((1, 'e8t11_point1_'), (1, 'round4_point2_rejectedContribution'), (2, 'e8t11_point2_'),
                          (3, 'e8t11_point3_'), (5, 'e8t11_point5_'), (6, 'e8t11_point6_')):
        failed = [name for name, outcome in results.items() if marker in name and outcome == 'failure']
        assert failed, f'point {point} ({marker}) has no assertion failure'
        print(f'Point {point}: {len(failed)} assertion failure(s) for {marker}', flush=True)
finally:
    for path, content in saved.items():
        (root / path).write_bytes(content)
    assert all((root / path).read_bytes() == content for path, content in saved.items())
    print('All working-tree source bytes restored.', flush=True)
sys.exit(0)
