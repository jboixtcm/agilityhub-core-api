#!/usr/bin/env python3
"""Temporarily reinstate the reviewed activity race and pack route transaction defect; always restore sources."""
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[3]
activity = root / 'src/main/java/com/agilityhub/core/clubs/activities/application/ActivityRegistrationService.java'
filter_file = root / 'src/main/java/com/agilityhub/core/shared/api/IdempotencyFilter.java'
originals = {p: p.read_text() for p in (activity, filter_file)}
try:
    activity.write_text(originals[activity].replace('context.members.lockBooking(memberId); ', ''))
    filter_file.write_text(originals[filter_file].replace('|POST /api/v1/pack-balances(/[^/]+/adjustments)?', ''))
    command = ['./mvnw', '-q', '-Dit.test=LifecycleConcurrencyIT#T_13_09*+T_12_07*', 'test-compile', 'failsafe:integration-test', 'failsafe:verify']
    print('Mutation: remove activity member lock and pack route service-owned transaction registration', flush=True)
    print('Command: ' + ' '.join(command), flush=True)
    result = subprocess.run(command, cwd=root, timeout=180)
    print(f'Maven exit: {result.returncode}', flush=True)
    report = ET.parse(root / 'target/failsafe-reports/TEST-com.agilityhub.core.clubs.bookings.api.LifecycleConcurrencyIT.xml').getroot()
    failures = [t.attrib['name'] for t in report.findall('testcase') if t.find('failure') is not None]
    print('Failing tests: ' + ', '.join(failures), flush=True)
    assert result.returncode == 1 and len(failures) == 2 and report.attrib['errors'] == '0', 'Expected only the two reviewed regressions'
    print('PASS: both defects detected; source files restored in finally', flush=True)
finally:
    for path, text in originals.items():
        path.write_text(text)
