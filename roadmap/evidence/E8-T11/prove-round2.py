#!/usr/bin/env python3
"""Reproduce E101's five review points from committed files, without writing git state."""
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
BASE = '8e4eb2a'
PATHS = ['src/main/java/com/agilityhub/core/payments/application/PaymentRefunds.java', '.github/workflows/ci.yml']
saved = {p: (ROOT / p).read_bytes() for p in PATHS}

def run(args, report, markers):
    print('$ ' + ' '.join(args), flush=True)
    result = subprocess.run(args, cwd=ROOT, timeout=1800)
    print('Exit:', result.returncode, flush=True)
    assert result.returncode == 1
    suite = ET.parse(ROOT / report).getroot()
    assert int(suite.get('errors')) == 0, 'Expected assertion failures, not test errors'
    failures = [case.get('name') for case in suite.iter('testcase') if case.find('failure') is not None]
    for marker in markers:
        found = [name for name in failures if marker in name]
        assert found, f'No regression for {marker}'
        print(marker + ': ' + str(len(found)) + ' assertion failures', flush=True)
    print('tests=' + suite.get('tests') + ' failures=' + suite.get('failures') + ' errors=' + suite.get('errors'), flush=True)

try:
    for p in PATHS:
        (ROOT / p).write_bytes(subprocess.check_output(['git', 'show', BASE + ':' + p], cwd=ROOT))
    run(['./mvnw', '-q', '-Dtest=CiWorkflowTest', 'test'],
        'target/surefire-reports/TEST-com.agilityhub.core.configuration.CiWorkflowTest.xml', ['round2_point5_'])
    run(['./mvnw', '-q', '-Dit.test=CapturedPaymentInvariantsIT#T_12_17_e8t11_round2*',
         'test-compile', 'failsafe:integration-test', 'failsafe:verify'],
        'target/failsafe-reports/TEST-com.agilityhub.core.payments.api.CapturedPaymentInvariantsIT.xml',
        ['round2_point1_', 'round2_point2_', 'round2_point3_', 'round2_point4_'])
finally:
    for p, content in saved.items():
        (ROOT / p).write_bytes(content)
    assert all((ROOT / p).read_bytes() == content for p, content in saved.items())
    print('All working-tree source bytes restored.', flush=True)
