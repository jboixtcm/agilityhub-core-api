#!/usr/bin/env python3
"""Snapshot executable inputs and all documentation used by contract tests."""
import argparse
import hashlib
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
MANIFEST = HERE / 'session7-inputs.json'


def inputs():
    paths = [ROOT / name for name in ('pom.xml', 'mvnw', 'Dockerfile')]
    for name in ('src', 'bin', 'deploy', 'seeds', '.mvn', 'docs'):
        paths.extend(path for path in (ROOT / name).rglob('*')
                     if path.is_file() and '__pycache__' not in path.parts and path.suffix != '.pyc')
    return {str(path.relative_to(ROOT)): ' '.join(
        hashlib.sha256(path.read_bytes()).hexdigest()[i:i + 8] for i in range(0, 64, 8))
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
        print(f'Recorded {len(current)} inputs, including all docs read by contract tests.')
        return 0
    expected = json.loads(MANIFEST.read_text())
    changed = sorted(path for path in expected.keys() | current.keys() if expected.get(path) != current.get(path))
    if changed:
        print('Changed inputs:\n' + '\n'.join(changed))
        return 1
    print(f'PASS: all {len(current)} inputs, including documentation, are unchanged.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
