#!/usr/bin/env python3
"""Inspect round-two verification inputs and results without changing product or git state."""
from pathlib import Path
import hashlib
import json
import subprocess
import sys
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent
mode = sys.argv[1]
if mode == 'scope':
    before = json.loads(subprocess.check_output(['git', 'show', (root / 'round2-base-revision.txt').read_text().strip()+':docs/openapi/openapi.json']))
    after = json.loads(Path('docs/openapi/openapi.json').read_text())
    def operations(api):
        return {(method.upper(), path): operation for path, methods in api['paths'].items()
                for method, operation in methods.items() if method in {'get','post','put','patch','delete','head','options'}}
    old, new = operations(before), operations(after)
    added = set(new) - set(old)
    changed = {key for key in old.keys() & new.keys() if old[key] != new[key]}
    assert added == {('POST', '/api/v1/platform/clubs/{clubId}/upload-url')}, added
    assert not set(old) - set(new)
    assert changed == {('POST', '/api/v1/platform/clubs')}, changed
    for method, path in sorted(added): print(f'Added: {method} {path}')
    for method, path in sorted(changed):
        left, right = dict(old[(method,path)]), dict(new[(method,path)])
        left.pop('description'); right.pop('description')
        assert left == right
        print(f'Changed description only: {method} {path}')
    old, new = before['components']['schemas'], after['components']['schemas']
    added = set(new) - set(old)
    changed = {key for key in old.keys() & new.keys() if old[key] != new[key]}
    assert added == {'DomainVerificationChallenge', 'PlatformThemeUploadRequest'}, added
    assert changed == {'PlatformDomain', 'PlatformProviderSummary', 'PlatformThemeRequest'}, changed
    assert not set(old) - set(new)
    print('Added schemas: ' + ', '.join(sorted(added)))
    print('Changed schemas: ' + ', '.join(sorted(changed)))
    print('All other operations and schemas unchanged; PlatformClubPage.appliedFilters already existed at session start.')
elif mode in {'capture', 'inputs'}:
    manifest = root / 'round2-inputs.json'
    def digest(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()
    if mode == 'capture':
        assert not manifest.exists(), 'Never overwrite verification inputs'
        files = subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','-z']).decode().split('\0')
        files = sorted({p for p in files if p.startswith(('src/','docs/','seeds/','.mvn/','bin/','.github/','deploy/','docker/','perf/'))
                        or p in {'pom.xml','mvnw','Dockerfile','compose.yaml','docker-compose.consumer.yml','.gitleaks.toml','.gitleaksignore'}})
        manifest.write_text(json.dumps([{'path': p, 'sha256': digest(p)} for p in files if Path(p).is_file()], indent=2)+'\n')
        print(f'Captured {len(json.loads(manifest.read_text()))} source, test, build and documentation inputs.')
    else:
        rows = json.loads(manifest.read_text())
        changed = [row['path'] for row in rows if not Path(row['path']).is_file() or digest(row['path']) != row['sha256']]
        assert not changed, changed
        print(f'All {len(rows)} source, test, build and documentation inputs match the clean run.')
elif mode == 'summary':
    exit_file = root / (sys.argv[2] + '.exit')
    print('Verification exit: ' + exit_file.read_text().strip())
    changed = {'E10ContractIT','E10ResponseContractTest','PlatformClubContractGuardsTest'}
    for kind in ('surefire','failsafe'):
        totals = dict.fromkeys(('tests','failures','errors','skipped'),0)
        classes, failures = [], []
        for path in sorted(Path('target',kind+'-reports').glob('TEST-*.xml')):
            suite = ET.parse(path).getroot()
            counts = {key:int(suite.get(key,'0')) for key in totals}
            for key, value in counts.items(): totals[key] += value
            name = suite.get('name','').rsplit('.',1)[-1]
            if name in changed: classes.append('  '+name+': '+' '.join(f'{key}={value}' for key,value in counts.items()))
            for case in suite.findall('testcase'):
                if case.find('failure') is not None or case.find('error') is not None:
                    failures.append('  Failed: '+name+'.'+case.get('name',''))
        print(kind+' totals: '+' '.join(f'{key}={value}' for key,value in totals.items()))
        for line in classes + failures: print(line)
elif mode == 'ci':
    head = subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
    runs = json.loads(subprocess.check_output(['gh','run','list','--commit',head,'--json','databaseId,status,conclusion,url','--limit','10']))
    print('Published HEAD: '+head[:8]+' [truncated]')
    if not runs: print('No CI run exists for the current published HEAD.')
    for run in runs:
        print(run['url']+' '+run['status']+' / '+str(run['conclusion']))
        jobs = json.loads(subprocess.check_output(['gh','run','view',str(run['databaseId']),'--json','jobs']))['jobs']
        for job in jobs: print('  '+job['name']+': '+job['status']+' / '+str(job['conclusion']))
    print('Round-two edits are local. The publisher-created commit and its final CI run do not exist yet.')
else:
    raise SystemExit('Unknown mode')
