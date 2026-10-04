#!/usr/bin/env python3
"""Resume an incomplete PIT attempt in bounded batches without reducing its scope."""
from pathlib import Path
import collections
import gzip
import json
import re
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
LOCK = '/Users/jordib/Dropbox/Documents/SOFTWARE_CANIC/05-desenvolupament/roadmap-kit/mac/heavy.sh'
IMAGE = 'ghcr.io/jboixtcm/agilityhub-core-api:e11-security-resumed'

def run(label, *command, expected=0):
    result = subprocess.run(['python3', str(HERE / 'resume-command.py'), label, *command], cwd=ROOT)
    assert result.returncode == expected, f'{label}: exit {result.returncode}, expected {expected}'

deadline = time.monotonic() + 3600
while True:
    matches = re.findall(r'^exit (-?\d+)$', (HERE / '141-pitest-resumed.log').read_text(), re.M)
    if matches:
        break
    assert time.monotonic() < deadline, 'The original runner did not report completion'
    time.sleep(2)
if int(matches[-1]) == 0:
    print('Original mutation run completed; its existing suite continues the remaining proofs')
    raise SystemExit(0)

raw = (ROOT / 'target/pit-reports/mutations.xml').read_bytes()
with (HERE / 'pitest-attempt141-incomplete.xml.gz').open('xb') as output:
    output.write(gzip.compress(raw, mtime=0))
source = raw.decode()
partial = ET.fromstring(source[:source.rfind('</mutation>') + 11] + '</mutations>')
summary = {'complete': False, 'exit': int(matches[-1]), 'completeRecords': len(partial),
           'statuses': dict(collections.Counter(mutation.get('status') for mutation in partial)),
           'reason': 'Original attempt incomplete; resume the same targets and mutators with history and batches of 25'}
(HERE / 'pitest-attempt141-incomplete.json').write_text(json.dumps(summary, indent=2) + '\n')
shutil.copyfile(ROOT / '.local/pitest/history.bin', ROOT / '.local/pitest/attempt141-final-history.bin')
run('153-pitest-batched.log', LOCK, './mvnw', '-q', '-Pmutation', '-DmutationUnitSize=25',
    'test-compile', 'org.pitest:pitest-maven:mutationCoverage')
run('142-mutation-summary.log', 'python3', 'roadmap/evidence/E11-T03/mutation-summary.py', '--log', '153-pitest-batched.log')
run('143-image-build.log', LOCK, 'docker', 'build', '-t', IMAGE, '.')
run('144-image-scan.log', 'bin/security-scan', 'image', IMAGE)
run('145-dependency-audit.log', 'bin/security-scan', 'rootfs', 'target')
run('146-compose-positive.log', LOCK, 'bin/deploy-smoke', '--image-tag', 'e11-security-resumed', '--security-only')
dockerfile = ROOT / '.local/e11-broken.Dockerfile'
dockerfile.write_text(f'FROM {IMAGE}\nENTRYPOINT ["/bin/false"]\n')
run('147-broken-image-build.log', 'docker', 'build', '-t', IMAGE + '-broken', '--file', str(dockerfile), '.')
run('148-compose-negative.log', LOCK, 'bin/deploy-smoke', '--image-tag', 'e11-security-resumed-broken', '--security-only', expected=1)
