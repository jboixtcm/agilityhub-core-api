#!/usr/bin/env python3
"""Sequential E11-T03 proofs; each command has its own immutable evidence log."""
from pathlib import Path
import os, shlex, subprocess, time, json, xml.etree.ElementTree as ET
ROOT = Path(__file__).resolve().parents[3]
EVIDENCE = Path(__file__).resolve().parent
LOCK = '/Users/jordib/Dropbox/Documents/SOFTWARE_CANIC/05-desenvolupament/roadmap-kit/mac/heavy.sh'
ENV = dict(os.environ, MAVEN_OPTS='-Dmaven.repo.local=/private/tmp/e11-maven.BJqwxL/repository', BUILDX_CONFIG='/private/tmp/e11-security-tools/buildx')
IMAGE = 'ghcr.io/jboixtcm/agilityhub-core-api:e11-security-local'
def run(number, label, command, expected=0, input=None):
    path = EVIDENCE / f'{number:02}-{label}.log'
    assert not path.exists(), f'Refusing to overwrite {path}'
    start = time.monotonic()
    with path.open('w') as log:
        log.write('COMMAND ' + shlex.join(command) + '\n'); log.flush()
        result = subprocess.run(command, input=input, text=True, cwd=ROOT, env=ENV, stdout=log, stderr=subprocess.STDOUT)
        elapsed = time.monotonic() - start
        log.write(f'\nexit {result.returncode}\nelapsed_seconds {elapsed:.3f}\n')
    print(f'{path.name}: exit {result.returncode}, {elapsed:.1f}s', flush=True)
    if (expected == 'nonzero' and result.returncode == 0) or (expected != 'nonzero' and result.returncode != expected):
        raise SystemExit(f'Unexpected result: {path.name}')
    return result.returncode

def summary():
    names = ['SecurityInventoryIT','SecurityHardeningIT','AnonymousRateLimitsTest','RequestTraceFilterTest','LogPrivacyTest','MongoTimeoutConfigurationTest','CorsIT','ClubCorsConfigurationSourceTest','HealthIndependenceIT','ActivityIT','SignupSecurityFixesIT','WaitlistIT','IdentityCoreIT','MongoRequestTimeoutIT','DemoScenarioSeedIT','TrainingIT']
    lines = ['COMMAND python3 roadmap/evidence/E11-T03/test-summary.py', 'clean verify exit 0']
    for directory in ['surefire-reports','failsafe-reports']:
        totals = dict.fromkeys(['tests','failures','errors','skipped'], 0)
        for path in sorted((ROOT/'target'/directory).glob('TEST-*.xml')):
            root = ET.parse(path).getroot()
            for key in totals: totals[key] += int(root.get(key, '0'))
            if root.get('name','').rsplit('.',1)[-1] in names:
                lines.append(root.get('name') + ' ' + ' '.join(f'{key}={root.get(key,"0")}' for key in totals))
        lines.append(directory + ' TOTAL ' + ' '.join(f'{key}={value}' for key,value in totals.items()))
    lines.append('exit 0')
    (EVIDENCE/'clean-test-summary.log').write_text('\n'.join(lines)+'\n')

if __name__ == '__main__':
    run(68,'image-build-json-appender',[LOCK,'docker','build','-t',IMAGE,'.'])
    run(69,'image-scan-json-appender',['bin/security-scan','image',IMAGE])
    run(70,'compose-positive-json-appender',[LOCK,'bin/deploy-smoke','--image-tag','e11-security-local','--security-only'])
    run(50,'caddy-counterfactual',[LOCK,'python3','roadmap/evidence/E11-T03/caddy-counterfactual.py'])
    run(42,'broken-image-build',['docker','build','-t','ghcr.io/jboixtcm/agilityhub-core-api:e11-security-broken','--file','-','.'],input='FROM '+IMAGE+'\nENTRYPOINT ["/bin/false"]\n')
    run(43,'compose-negative',[LOCK,'bin/deploy-smoke','--image-tag','e11-security-broken','--security-only'], expected='nonzero')
    run(71,'clean-verify-final',[LOCK,'./mvnw','-q','clean','verify'])
    summary()
    snapshot = Path('/private/tmp/e11-security-tools/openapi-committed.json')
    run(72,'openapi-first-final',['bin/openapi-snapshot'])
    run(73,'openapi-first-final-cmp',['cmp',str(snapshot),'docs/openapi/openapi.json'])
    run(74,'openapi-second-final',['bin/openapi-snapshot'])
    run(75,'openapi-second-final-cmp',['cmp',str(snapshot),'docs/openapi/openapi.json'])
    run(76,'dependency-rootfs-final',['bin/security-scan','rootfs','target'])
    run(44,'pitest-fixture-timeout',[LOCK,'./mvnw','-q','-Pmutation','test-compile','org.pitest:pitest-maven:mutationCoverage'])
    run(64,'mutation-summary',['python3','roadmap/evidence/E11-T03/mutation-summary.py'])
