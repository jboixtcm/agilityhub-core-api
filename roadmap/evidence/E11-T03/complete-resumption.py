#!/usr/bin/env python3
"""Sequential, foreground verification after the organizer's bounded PIT run."""
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
LOCK = '/Users/jordib/Dropbox/Documents/SOFTWARE_CANIC/05-desenvolupament/roadmap-kit/mac/heavy.sh'
IMAGE = 'ghcr.io/jboixtcm/agilityhub-core-api:e11-security-oct05'


def run(label, *command, expected=0):
    result = subprocess.run(['python3', str(HERE / 'resume-command.py'), label, *command], cwd=ROOT)
    assert result.returncode == expected, f'{label}: {result.returncode}, expected {expected}'


deadline = time.monotonic() + 3600
while '\nexit ' not in (HERE / '166-pitest-bounded.log').read_text():
    assert time.monotonic() < deadline, 'PIT has not finished; no second build started'
    time.sleep(2)
assert '\nexit 0\n' in (HERE / '166-pitest-bounded.log').read_text()
run('167-mutation-summary.log', 'python3', str(HERE / 'mutation-summary.py'), '--log', '166-pitest-bounded.log', '--prefix', 'pitest-bounded')
run('168-clean-verify-final.log', LOCK, 'env', 'JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=2', './mvnw', '-q', 'clean', 'verify')
run('169-clean-test-summary.log', 'python3', str(HERE / 'test-summary.py'))
baseline = ROOT / '.local/e11-openapi-committed.json'
run('170-openapi-first.log', LOCK, 'bin/openapi-snapshot')
run('171-openapi-first-cmp.log', 'cmp', str(baseline), 'docs/openapi/openapi.json')
run('172-openapi-second.log', LOCK, 'bin/openapi-snapshot')
run('173-openapi-second-cmp.log', 'cmp', str(baseline), 'docs/openapi/openapi.json')
run('174-image-build.log', LOCK, 'docker', 'build', '-t', IMAGE, '.')
run('175-image-scan.log', 'bin/security-scan', 'image', IMAGE)
run('176-dependency-audit.log', 'bin/security-scan', 'rootfs', 'target')
run('177-compose-positive.log', LOCK, 'bin/deploy-smoke', '--image-tag', 'e11-security-oct05', '--security-only')
dockerfile = ROOT / '.local/e11-broken.Dockerfile'
dockerfile.write_text(f'FROM {IMAGE}\nENTRYPOINT ["/bin/false"]\n')
run('178-broken-image-build.log', LOCK, 'docker', 'build', '-t', IMAGE + '-broken', '--file', str(dockerfile), '.')
run('179-compose-negative.log', LOCK, 'bin/deploy-smoke', '--image-tag', 'e11-security-oct05-broken', '--security-only', expected=1)
