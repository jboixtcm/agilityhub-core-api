#!/usr/bin/env python3
"""Audit this resumption's completed evidence with unmodified default Gitleaks rules."""
import argparse
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--through', type=int, required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    if args.report.exists():
        parser.error('Report already exists; choose a new attempt.')
    selected = []
    for path in HERE.iterdir():
        number = re.match(r'(\d+)-', path.name)
        if path.is_file() and ((number and 130 <= int(number.group(1)) <= args.through)
                               or path.name.startswith('session7-') or path.name == 'progress.json'):
            selected.append(path)
    with tempfile.TemporaryDirectory(prefix='e11-t06-session7-audit-') as directory:
        for path in selected:
            shutil.copyfile(path, Path(directory) / path.name)
        print(f'Auditing {len(selected)} session-7 evidence files through attempt {args.through}; '
              'default rules, no repository evidence exclusions.', flush=True)
        return subprocess.run([str(ROOT / '.local/security-tools/gitleaks'), 'dir', directory,
                               '--config', str(HERE / 'secret-audit.toml'), '--redact', '--no-banner',
                               '--report-format', 'json', '--report-path', str(args.report.resolve())]).returncode


if __name__ == '__main__':
    raise SystemExit(main())
