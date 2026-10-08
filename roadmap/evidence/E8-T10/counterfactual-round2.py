#!/usr/bin/env python3
"""Run Round 2 regressions against the reviewed behavior; restore source bytes even on failure."""
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

root = Path('src/main/java/com/agilityhub/core')
paths = [root / 'migration/application/PlayoffImportService.java', root / 'shared/persistence/TenantWriteTracking.java']
before = {path: path.read_bytes() for path in paths}
try:
    current = before[paths[0]].decode()
    begin = current.index('                    var result = transactions.execute(status -> {')
    end = current.index('                    long expected =', begin)
    block = current[begin:end]
    assert block.count('runs.fence(holder, clock.instant());') == 2
    # Keep the maintenance protocol, removing only the two lease checks being tested.
    paths[0].write_text(current[:begin] + block.replace('                        runs.fence(holder, clock.instant());\n', '') + current[end:])
    paths[1].write_text(before[paths[1]].decode().replace('@Component\n', ''))
    command = ['./mvnw', '-q', '-Dtest=NoTests', '-Dsurefire.failIfNoSpecifiedTests=false',
               '-Dit.test=PlayoffMigrationIT#*round2*,TenantWriteTrackingIT',
               'test-compile', 'failsafe:integration-test', 'failsafe:verify']
    print('Counterfactual command: ' + ' '.join(command), flush=True)
    result = subprocess.run(command, timeout=1800)
    print('Counterfactual Maven exit: ' + str(result.returncode), flush=True)
    assert result.returncode == 1
    failures = errors = tests = 0
    for name in ('com.agilityhub.core.migration.PlayoffMigrationIT', 'com.agilityhub.core.shared.persistence.TenantWriteTrackingIT'):
        suite = ET.parse(Path('target/failsafe-reports', 'TEST-' + name + '.xml')).getroot()
        print(name + ': ' + ' '.join(k + '=' + suite.get(k) for k in ('tests', 'failures', 'errors', 'skipped')))
        failures += int(suite.get('failures')); errors += int(suite.get('errors')); tests += int(suite.get('tests'))
    assert tests == 22 and failures == 20 and errors == 0
finally:
    for path, content in before.items():
        path.write_bytes(content)
    assert all(path.read_bytes() == content for path, content in before.items())
    print('All production source bytes restored', flush=True)

path = 'src/test/java/com/agilityhub/core/migration/PlayoffMigrationIT.java'
old = subprocess.check_output(['git', 'show', 'e025b4f:' + path], text=True)
new = Path(path).read_text()
assert old.count('void T_18_03_point1_') == 3
assert new.count('void T_18_03_point1_') == 0
assert new.count('void T_18_13_point1_') == 3
print('Point 3: all three recovery tests now use T-18-13 / R-18-03')
