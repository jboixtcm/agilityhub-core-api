#!/usr/bin/env python3
"""Check the resumed S17 handoff without modifying product or git state."""
from pathlib import Path
import hashlib
import json
import subprocess
import sys
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent
mode = sys.argv[1]

def tracked_inputs():
    files = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z']).decode().split('\0')
    return {p for p in files if Path(p).is_file() and (
        p.startswith(('src/', 'docs/', 'seeds/', '.mvn/', 'bin/', '.github/', 'deploy/', 'docker/', 'perf/'))
        or p in {'pom.xml', 'mvnw', 'Dockerfile', 'compose.yaml', 'docker-compose.consumer.yml', '.gitleaks.toml', '.gitleaksignore'})}

if mode == 'inputs':
    rows = json.loads((root / 'round2-resume-inputs.json').read_text())
    assert tracked_inputs() == {row['path'] for row in rows}, 'Input inventory changed'
    changed = [row['path'] for row in rows if hashlib.sha256(Path(row['path']).read_bytes()).hexdigest() != row['sha256']]
    assert not changed, changed
    print(f'All {len(rows)} source, test, build and documentation inputs match fresh clean verification 70.')
elif mode == 'scope':
    baseline = (root / 'round2-base-revision.txt').read_text().strip()
    before = json.loads(subprocess.check_output(['git', 'show', baseline + ':docs/openapi/openapi.json']))
    after = json.loads(Path('docs/openapi/openapi.json').read_text())
    # S16 round 3 was published before this resume. Check that its delta is unchanged.
    published = json.loads(subprocess.check_output(['git', 'show', '1485fde9:docs/openapi/openapi.json']))
    assert after == published, 'Snapshot differs from this resume baseline'
    def operations(api):
        return {(method.upper(), path): value for path, methods in api['paths'].items()
                for method, value in methods.items() if method in {'get', 'post', 'put', 'patch', 'delete', 'head', 'options'}}
    old, new = operations(before), operations(after)
    assert set(new) - set(old) == {('POST', '/api/v1/platform/clubs/{clubId}/upload-url')}
    assert not set(old) - set(new)
    changed = {key for key in old.keys() & new.keys() if old[key] != new[key]}
    assert changed == {('POST', '/api/v1/platform/clubs'), ('GET', '/api/v1/courses/{id}'), ('GET', '/api/v1/ring-setups/{id}')}, changed
    left, right = dict(old[('POST', '/api/v1/platform/clubs')]), dict(new[('POST', '/api/v1/platform/clubs')])
    left.pop('description'); right.pop('description')
    assert left == right
    old, new = before['components']['schemas'], after['components']['schemas']
    assert set(new) - set(old) == {'DomainVerificationChallenge', 'PlatformThemeUploadRequest'}
    assert not set(old) - set(new)
    changed = {name for name in old.keys() & new.keys() if old[name] != new[name]}
    assert changed == {'PlatformDomain', 'PlatformProviderSummary', 'PlatformThemeRequest', 'RingSetup', 'MemberRingSetup'}, changed
    print('S17 adds only POST /platform/clubs/{clubId}/upload-url and two schemas.')
    print('S17 changes only the create-club description and PlatformDomain, PlatformProviderSummary, PlatformThemeRequest.')
    print('PlatformClubPage.appliedFilters was already present in the implementation baseline.')
    print('Published S16 round-3 deltas (two operations, two schemas) are preserved unchanged.')
    print('The complete current snapshot equals the published resume baseline; no unrelated contract drift.')
elif mode == 'summary':
    code = int((root / '70-round2-resume-clean-verify.exit').read_text())
    print(f'Clean verification exit: {code}')
    assert code == 0
    expected = {'E10ContractIT', 'E10ResponseContractTest', 'PlatformClubContractGuardsTest'}
    seen = set()
    for kind in ('surefire', 'failsafe'):
        totals = dict.fromkeys(('tests', 'failures', 'errors', 'skipped'), 0)
        classes = []
        for path in sorted(Path('target', kind + '-reports').glob('TEST-*.xml')):
            suite = ET.parse(path).getroot()
            counts = {key: int(suite.get(key, '0')) for key in totals}
            for key, value in counts.items(): totals[key] += value
            name = suite.get('name', '').rsplit('.', 1)[-1]
            if name in expected:
                seen.add(name)
                classes.append('  ' + name + ': ' + ' '.join(f'{key}={value}' for key, value in counts.items()))
        print(kind + ' totals: ' + ' '.join(f'{key}={value}' for key, value in totals.items()))
        for line in classes: print(line)
        assert totals['tests'] > 0 and totals['failures'] == totals['errors'] == totals['skipped'] == 0
    assert seen == expected, expected - seen
elif mode == 'boundaries':
    rows = json.loads((root / 'round2-resume-protected.json').read_text())
    for row in rows:
        value = (Path('roadmap/tasks/E10-T01.md').read_text().split('## Organizer verification', 1)[1].encode()
                 if row['path'] == 'organizer' else Path(row['path']).read_bytes())
        assert hashlib.sha256(value).hexdigest() == row['sha256'], row['path']
    changed = subprocess.check_output(['git', 'diff', '--name-only'], text=True).splitlines()
    assert all(p in {'CHANGELOG.md', 'roadmap/STATUS.md', 'roadmap/MESSAGES.md', 'roadmap/tasks/E10-T01.md'}
               or p.startswith('roadmap/evidence/E10-T01/') for p in changed), changed
    print('All other task files, ROADMAP and Organizer verification are unchanged from session start.')
    print('Resume changes are limited to E10-T01 report/evidence, changelog and roadmap handoff/state.')
else:
    raise SystemExit('Unknown check')
