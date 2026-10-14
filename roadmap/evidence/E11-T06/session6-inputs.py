#!/usr/bin/env python3
"""Record/check all verification inputs without reading local credentials or build output."""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MANIFEST = Path(__file__).with_name('session6-inputs.json')


def inputs():
    paths = [ROOT / name for name in ('pom.xml', 'mvnw', 'Dockerfile')]
    for directory in ('src', 'bin', 'deploy', 'seeds', '.mvn', 'docs/openapi'):
        paths.extend(path for path in (ROOT / directory).rglob('*')
                     if path.is_file() and '__pycache__' not in path.parts and path.suffix != '.pyc')
    return {str(path.relative_to(ROOT)): ' '.join(
        hashlib.sha256(path.read_bytes()).hexdigest()[n:n + 8] for n in range(0, 64, 8))
        for path in sorted(paths)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('record', 'check'))
    args = parser.parse_args()
    current = inputs()
    if args.mode == 'record':
        with MANIFEST.open('x') as output:
            json.dump(current, output, indent=2)
            output.write('\n')
        print(f'Recorded {len(current)} verification inputs; source, tests, build, deployment and snapshot.')
    else:
        expected = json.loads(MANIFEST.read_text())
        changed = sorted(path for path in expected.keys() | current.keys()
                         if expected.get(path) != current.get(path))
        if changed:
            print('Changed inputs:\n' + '\n'.join(changed))
            return 1
        print(f'PASS: all {len(current)} verification inputs are unchanged.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
