#!/usr/bin/env python3
"""Continue the ordered verification after the resumed clean build finishes."""
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
LOCK = '/Users/jordib/Dropbox/Documents/SOFTWARE_CANIC/05-desenvolupament/roadmap-kit/mac/heavy.sh'
IMAGE = 'ghcr.io/jboixtcm/agilityhub-core-api:e11-security-resumed'

def run(label, *command, expected=0):
    result = subprocess.run(['python3', str(HERE / 'resume-command.py'), label, *command], cwd=ROOT)
    assert result.returncode == expected, f'{label}: exit {result.returncode}, expected {expected}'

deadline = time.monotonic() + 3600
while '\nexit ' not in (HERE / '120-clean-verify-resumed.log').read_text():
    assert time.monotonic() < deadline, 'Clean build did not finish within one hour'
    time.sleep(2)
assert '\nexit 0\n' in (HERE / '120-clean-verify-resumed.log').read_text(), 'Clean build failed'
run('136-clean-test-summary.log', 'python3', 'roadmap/evidence/E11-T03/test-summary.py')
baseline = ROOT / '.local/e11-openapi-committed.json'
baseline.write_bytes(subprocess.check_output(['git', 'show', 'HEAD:docs/openapi/openapi.json'], cwd=ROOT))
run('137-openapi-first.log', 'bin/openapi-snapshot')
run('138-openapi-first-cmp.log', 'cmp', str(baseline), 'docs/openapi/openapi.json')
run('139-openapi-second.log', 'bin/openapi-snapshot')
run('140-openapi-second-cmp.log', 'cmp', str(baseline), 'docs/openapi/openapi.json')
run('141-pitest-resumed.log', LOCK, './mvnw', '-q', '-Pmutation', 'test-compile', 'org.pitest:pitest-maven:mutationCoverage')
run('142-mutation-summary.log', 'python3', 'roadmap/evidence/E11-T03/mutation-summary.py', '--log', '141-pitest-resumed.log')
run('143-image-build.log', LOCK, 'docker', 'build', '-t', IMAGE, '.')
run('144-image-scan.log', 'bin/security-scan', 'image', IMAGE)
run('145-dependency-audit.log', 'bin/security-scan', 'rootfs', 'target')
run('146-compose-positive.log', LOCK, 'bin/deploy-smoke', '--image-tag', 'e11-security-resumed', '--security-only')
dockerfile = ROOT / '.local/e11-broken.Dockerfile'
dockerfile.write_text(f'FROM {IMAGE}\nENTRYPOINT ["/bin/false"]\n')
run('147-broken-image-build.log', 'docker', 'build', '-t', IMAGE + '-broken', '--file', str(dockerfile), '.')
run('148-compose-negative.log', LOCK, 'bin/deploy-smoke', '--image-tag', 'e11-security-resumed-broken', '--security-only', expected=1)
