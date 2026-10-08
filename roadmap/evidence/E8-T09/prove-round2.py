#!/usr/bin/env python3
"""Reproduce the five review points against the published pre-fix tree, then restore every byte."""
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[3]
paths = subprocess.check_output(['git', 'diff', '--name-only', '--', 'src/main/java'], text=True).splitlines()
paths.append('src/test/java/com/agilityhub/core/payments/api/CardPaymentsIT.java')
saved = {path: (root / path).read_bytes() for path in paths}
try:
    for path in paths:
        content = subprocess.check_output(['git', 'show', 'HEAD:' + path])
        if path.endswith('CardPaymentsIT.java'):
            content = content.replace(b'.containsExactlyInAnyOrder(1000, -1000);', b'.containsExactlyInAnyOrder(1000L, -1000L);')
        (root / path).write_bytes(content)
    result = subprocess.run(['./mvnw', '-q', '-Dit.test=CardPaymentsIT#*round2_point*',
                             'test-compile', 'failsafe:integration-test', 'failsafe:verify'])
    print('Counterfactual Maven exit:', result.returncode, flush=True)
    assert result.returncode != 0
    suite = ET.parse(root / 'target/failsafe-reports/TEST-com.agilityhub.core.payments.api.CardPaymentsIT.xml').getroot()
    failures = [case.get('name') for case in suite.findall('testcase') if case.find('failure') is not None]
    assert int(suite.get('errors')) == 0
    for point in range(1, 6):
        names = [name for name in failures if f'round2_point{point}_' in name]
        assert names, f'No assertion failure for point {point}'
        for name in names:
            print('Observed failing regression:', name, flush=True)
finally:
    for path, content in saved.items():
        (root / path).write_bytes(content)
    assert all((root / path).read_bytes() == content for path, content in saved.items())
    print('All working-tree source bytes restored.', flush=True)
