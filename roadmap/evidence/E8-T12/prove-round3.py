#!/usr/bin/env python3
"""Reproduce E104 against reviewed source, restoring it without git writes."""
from pathlib import Path
import shlex
import subprocess
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[3]
source = root / 'src/main/java/com/agilityhub/core/payments/application/PaymentRefunds.java'
saved = source.read_bytes()
baseline = subprocess.check_output(['git', 'show', '1f56e0b:' + str(source.relative_to(root))], cwd=root)
args = ['./mvnw', '-q', '-Dit.test=CapturedPaymentInvariantsIT#T_12_17_e8t12_round3*',
        'test-compile', 'failsafe:integration-test', 'failsafe:verify']
try:
    source.write_bytes(baseline)
    print('Reviewed PaymentRefunds: 1f56e0b; final regression tests retained.', flush=True)
    print('Command: ' + shlex.join(args), flush=True)
    result = subprocess.run(args, cwd=root, timeout=1800)
    print('Maven exit:', result.returncode, flush=True)
    assert result.returncode == 1
    suite = ET.parse(root / 'target/failsafe-reports/TEST-com.agilityhub.core.payments.api.CapturedPaymentInvariantsIT.xml').getroot()
    assert int(suite.get('errors')) == int(suite.get('skipped')) == 0
    cases = list(suite.iter('testcase'))
    assert len(cases) == 5
    for case in cases:
        assert case.find('failure') is not None, case.get('name')
        print(case.get('name') + ': assertion failure', flush=True)
    print('Totals: ' + ', '.join(key + '=' + suite.get(key) for key in ('tests', 'failures', 'errors', 'skipped')), flush=True)
finally:
    source.write_bytes(saved)
    assert source.read_bytes() == saved
    print('Final PaymentRefunds restored byte for byte.', flush=True)
