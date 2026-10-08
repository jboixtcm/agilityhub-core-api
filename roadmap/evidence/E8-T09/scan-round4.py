#!/usr/bin/env python3
"""Scan the complete round's changed files and all retained task evidence without git writes."""
import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[3]
parser = argparse.ArgumentParser()
parser.add_argument('--gitleaks', default='gitleaks')
args = parser.parse_args()
paths = set(subprocess.check_output(['git', 'diff', '0717e04', '--name-only'], cwd=root, text=True).splitlines())
paths.update(subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard'], cwd=root, text=True).splitlines())
paths.update(str(p.relative_to(root)) for p in Path(__file__).parent.iterdir() if p.is_file())
with tempfile.TemporaryDirectory(prefix='e8-t09-scan-') as directory:
    staging = Path(directory)
    for relative in sorted(paths):
        source = root / relative
        if source.is_file():
            destination = staging / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
    print(f'Scanning {len(paths)} changed/evidence paths with the unchanged repository policy.', flush=True)
    subprocess.run([args.gitleaks, 'dir', '--no-banner', '--redact', '--config', str(root / '.gitleaks.toml'),
                    str(staging)], check=True)
print('PASS complete round and retained task evidence secret scan')
