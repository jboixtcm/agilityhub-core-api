#!/usr/bin/env python3
"""Audit the evidence of sessions 8-11 (attempts 145 onwards, the session 8/10/11 helpers, pit/session10, pit/session11,
the session-10 mutation reports) and the session-11 survivor tests with the unmodified Gitleaks rules of
`secret-audit.toml` (as sessions 7 and 10), then map each finding back to its file for classify-trace-findings.py.

Usage: session11-secret-audit.py REPORT.json MAPPED.json
"""
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
TESTS = ['identity/application/SupportAccessServiceSurvivors3Test.java', 'identity/domain/SupportAccessEventsSurvivors3Test.java',
         'identity/application/DemoPlatformAccountsSeederSurvivors3Test.java', 'identity/api/MeControllerSurvivors3Test.java']


def main(report, mapped):
    if report.exists() or mapped.exists():
        raise SystemExit('Report already exists; choose a new attempt.')
    selected = []
    for path in HERE.iterdir():
        number = re.match(r'(\d+)-', path.name)
        if path.is_file() and ((number and int(number.group(1)) >= 145)
                               or path.name.startswith(('session8-', 'session10-', 'session11-')) or path.name == 'progress.json'):
            selected.append(path)
    selected += [path for folder in ('pit/session10', 'pit/session11') for path in (HERE / folder).rglob('*') if path.is_file()]
    selected += [path for path in (HERE / 'pit/session8').glob('*session10*') if path.is_file()]
    selected += [ROOT / 'src/test/java/com/agilityhub/core' / name for name in TESTS]
    with tempfile.TemporaryDirectory(prefix='e11-t06-session11-audit-') as directory:
        for index, path in enumerate(selected):
            shutil.copyfile(path, Path(directory) / f'{index:04d}-{path.name}')
        print(f'Auditing {len(selected)} files of sessions 8-11; default rules, no exclusions.', flush=True)
        status = subprocess.run([str(ROOT / '.local/security-tools/gitleaks'), 'dir', directory,
                                 '--config', str(HERE / 'secret-audit.toml'), '--redact', '--no-banner',
                                 '--report-format', 'json', '--report-path', str(report)]).returncode
    rows = json.loads(report.read_text())
    for row in rows:
        index = int(re.fullmatch(r'(\d{4})-.+', Path(row['File']).name).group(1))
        row['File'] = str(selected[index].relative_to(ROOT))
    with mapped.open('x') as output:
        json.dump(rows, output, indent=2)
        output.write('\n')
    print(f'gitleaks exit {status}; {len(rows)} findings mapped to {mapped.relative_to(ROOT)}', flush=True)
    if not rows:
        return status
    return subprocess.run([sys.executable, str(HERE / 'classify-trace-findings.py'), str(mapped)]).returncode


if __name__ == '__main__':
    raise SystemExit(main(Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve()))
