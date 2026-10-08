#!/usr/bin/env python3
"""Run the final regressions against the reviewed implementation; restore every byte in finally."""
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[3]
changed = subprocess.check_output(['git', 'diff', '--name-only', '--', 'src/main'], cwd=root, text=True).splitlines()
paths = [path for path in changed if not path.endswith('/FakePaymentProvider.java')]
saved = {path: (root / path).read_bytes() for path in paths}
try:
    for path in paths:
        (root / path).write_bytes(subprocess.check_output(['git', 'show', 'HEAD:' + path], cwd=root))
    command = ['./mvnw', '-q', '-Dit.test=CardPaymentsIT#*point*,LifecycleIT#*point*',
               'test-compile', 'failsafe:integration-test', 'failsafe:verify']
    print('Command: ' + ' '.join(command), flush=True)
    result = subprocess.run(command, cwd=root)
    print('Maven exit: ' + str(result.returncode), flush=True)
    failures = []
    for report in (root / 'target/failsafe-reports').glob('TEST-*.xml'):
        tree = ET.parse(report).getroot()
        for case in tree.findall('testcase'):
            if '_point' in case.attrib['name'] and (case.find('failure') is not None or case.find('error') is not None):
                failures.append(case.attrib['name'])
    for name in sorted(failures):
        print('Observed failing regression: ' + name, flush=True)
    for point in range(1, 11):
        assert any('_point' + str(point) + '_' in name for name in failures), f'Point {point} was not reproduced'
    assert result.returncode != 0, 'The reviewed implementation unexpectedly passed'
finally:
    for path, content in saved.items():
        (root / path).write_bytes(content)
    assert all((root / path).read_bytes() == content for path, content in saved.items())
    print('All working-tree source bytes restored.', flush=True)
