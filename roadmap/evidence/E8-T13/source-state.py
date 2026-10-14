"""Record or compare the exact source, test, contract and build inputs for this session."""
import hashlib
import json
from pathlib import Path
import sys

root = Path.cwd()
manifest = Path(__file__).with_name('source-state.json')
paths = []
for name in ('src', '.mvn', 'bin', 'schemas', 'seeds'):
    directory = root / name
    if directory.is_dir():
        paths.extend(path for path in directory.rglob('*') if path.is_file())
for name in ('pom.xml', 'mvnw', 'mvnw.cmd', 'docs/openapi/openapi.json'):
    if (root / name).is_file():
        paths.append(root / name)
current = {str(path.relative_to(root)): list(hashlib.sha256(path.read_bytes()).digest()) for path in sorted(set(paths))}
if '--record' in sys.argv:
    with manifest.open('x') as output:
        json.dump(current, output, indent=2)
    print(f'Recorded {len(current)} source, test, contract and build inputs.')
else:
    original = json.loads(manifest.read_text())
    changed = sorted(key for key in original.keys() | current.keys() if original.get(key) != current.get(key))
    print(f'Compared {len(current)} inputs; changed={len(changed)}.')
    print('\n'.join(changed))
    raise SystemExit(bool(changed))
