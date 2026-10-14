"""Record or verify the exact product/build/test inputs of the final clean run."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

paths = subprocess.check_output(['git', 'ls-files', '-z', '--', 'src', 'pom.xml', '.mvn', 'mvnw',
    'bin', '.github', 'docs/openapi', 'seeds', 'Dockerfile', 'compose.yaml', 'docker-compose.consumer.yml',
    'deploy', 'docker', 'perf']).decode().split('\0')
paths = sorted(p for p in set(filter(None, paths)) | {str(p) for p in Path('src').rglob('*') if p.is_file()} if Path(p).is_file())
current = [{'path': p, 'sha256': hashlib.sha256(Path(p).read_bytes()).hexdigest()} for p in paths]
destination = Path(sys.argv[2])
if sys.argv[1] == 'record':
    assert not destination.exists(), 'Never replace a recorded input snapshot'
    destination.write_text(json.dumps(current, indent=2) + '\n')
    print(f'Recorded {len(current)} source, test, build and OpenAPI inputs.')
else:
    expected = json.loads(destination.read_text())
    expected_map = {x['path']: x['sha256'] for x in expected}
    current_map = {x['path']: x['sha256'] for x in current}
    changed = [p for p in expected_map.keys() | current_map.keys() if expected_map.get(p) != current_map.get(p)]
    for p in sorted(changed):
        print('Changed: ' + p)
    assert not changed, 'Verification inputs changed'
    print(f'All {len(current)} inputs match the clean-run snapshot.')
