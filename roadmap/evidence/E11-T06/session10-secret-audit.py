#!/usr/bin/env python3
"""Audit the evidence of sessions 8-10 (attempts 145 onwards, session8/10 helpers, pit/session10, the session-10
mutation reports) with the unmodified Gitleaks rules of `secret-audit.toml` (as session 7)."""
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
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    if args.report.exists():
        parser.error('Report already exists; choose a new attempt.')
    selected = []
    for path in HERE.iterdir():
        number = re.match(r'(\d+)-', path.name)
        if path.is_file() and ((number and int(number.group(1)) >= 145)
                               or path.name.startswith(('session8-', 'session10-')) or path.name == 'progress.json'):
            selected.append(path)
    selected += [path for path in (HERE / 'pit/session10').glob('*') if path.is_file()]
    selected += [path for path in (HERE / 'pit/session8').glob('*session10*') if path.is_file()]
    with tempfile.TemporaryDirectory(prefix='e11-t06-session10-audit-') as directory:
        for index, path in enumerate(selected):
            shutil.copyfile(path, Path(directory) / f'{index:04d}-{path.name}')
        print(f'Auditing {len(selected)} evidence files of sessions 8-10; default rules, no exclusions.', flush=True)
        return subprocess.run([str(ROOT / '.local/security-tools/gitleaks'), 'dir', directory,
                               '--config', str(HERE / 'secret-audit.toml'), '--redact', '--no-banner',
                               '--report-format', 'json', '--report-path', str(args.report.resolve())]).returncode


if __name__ == '__main__':
    raise SystemExit(main())
