#!/usr/bin/env python3
"""Check the report against literal logs and the protected organizer section."""
import ast
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[3]
EVIDENCE = Path(__file__).resolve().parent
report = ROOT / 'roadmap/tasks/E11-T03.md'
text = report.read_text()
assert report.stat().st_size < 120000
syntax = ast.parse((EVIDENCE / 'render-evidence.py').read_text())
entries = next(ast.literal_eval(node.value) for node in syntax.body
               if isinstance(node, ast.Assign) and any(isinstance(target, ast.Name) and target.id == 'entries' for target in node.targets))
for filename, _ in entries:
    log = EVIDENCE / filename
    tail = '\n'.join(log.read_text().splitlines()[-40:])
    section = text.split('#### ' + filename + '\n', 1)[1].split('\n#### ', 1)[0]
    assert '```text\n' + tail + '\n```' in section, filename
    assert subprocess.run(['git', 'check-ignore', '-q', str(log)], cwd=ROOT).returncode == 1, filename
committed = subprocess.check_output(['git', 'show', 'HEAD:roadmap/tasks/E11-T03.md'], cwd=ROOT, text=True)
assert text.split('## Organizer verification', 1)[1] == committed.split('## Organizer verification', 1)[1]
subprocess.run(['git', 'diff', '--check'], cwd=ROOT, check=True)
subprocess.run(['git', 'diff', '--exit-code', '--', 'docs/openapi/openapi.json'], cwd=ROOT, check=True)
print('PASS literal tails for', len(entries), 'verification logs; each is publishable')
print('PASS task size', report.stat().st_size, 'bytes; organizer section unchanged')
print('PASS diff whitespace and unchanged OpenAPI snapshot')
