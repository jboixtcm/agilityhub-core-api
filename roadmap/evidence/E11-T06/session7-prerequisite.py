#!/usr/bin/env python3
"""Confirm the inherited E108 catalog mismatch without changing its owners' code."""
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
report = ROOT / 'target/failsafe-reports/TEST-com.agilityhub.core.configuration.E2ContractIT.xml'
name = 'T_02_03_allE2CatalogErrorsAlreadyExistWithCanonicalStatuses'
case = next(row for row in ET.parse(report).getroot().findall('testcase') if row.get('name') == name)
failure = case.find('failure')
assert failure is not None
message = failure.get('message', '')
missing = message.split('but could not find the following elements:', 1)[1]
actions = ('CLUB_CREATED', 'CLUB_PAYMENT_PROVIDER_CHANGED', 'CLUB_ADMIN_INVITED')
assert all(action in missing for action in actions)
spec = next((ROOT / 'docs/specs').glob('S14*.md'))
row = next(line for line in spec.read_text().splitlines() if line.startswith('| R-14-09 |'))
assert all(action in row for action in actions)
assert 'E10-T02' in row and 'E108' in row
messages = (ROOT / 'roadmap/MESSAGES.md').read_text()
assert '**E10-T02 → round 2** (seven points)' in messages
changed = subprocess.check_output(['git', 'diff', '--name-only'], cwd=ROOT, text=True).splitlines()
assert not any(path.startswith(('src/', 'docs/', 'bin/', 'deploy/', 'seeds/', '.mvn/'))
               or path in ('pom.xml', 'mvnw', 'Dockerfile') for path in changed)
print('Confirmed failure: E2ContractIT.' + name)
print('Missing catalogued API audit actions: ' + ', '.join(actions))
print('Authority: ' + str(spec.relative_to(ROOT)) + ' R-14-09; E108 assigns these to E10-T02.')
print('Ownership: roadmap/MESSAGES.md, organizer entry 14-10 morning, E10-T02 round 2.')
print('PASS inspection: test assertion retained; source, tests, build inputs and synced docs unmodified.')
print('Verification remains blocked; inspection exit 0 is not a successful build.')
