#!/usr/bin/env python3
"""E8-T10: check complete task scope, report ownership, whitespace and safe evidence."""
from pathlib import Path
import importlib.util
import re
import subprocess

BASE = 'e2ecd1e'
SCOPE_BASE = '94a3e77'
TASK = 'roadmap/tasks/E8-T10.md'


def git(*args):
    return subprocess.check_output(['git', *args], text=True)


subprocess.run(['python3', 'bin/check-whitespace.py', '--base', BASE], check=True)
print('Full task range plus working tree and untracked files: whitespace clean')
changed = set(git('diff', '--name-only', SCOPE_BASE).splitlines()) | set(git('ls-files', '--others', '--exclude-standard').splitlines())
for path in changed:
    if path.startswith('roadmap/'):
        assert path in {TASK, 'roadmap/STATUS.md', 'roadmap/MESSAGES.md'} or path.startswith('roadmap/evidence/E8-T10/'), path
assert 'roadmap/ROADMAP.md' not in changed
assert Path(TASK).read_text().split('## Organizer verification', 1)[1] == git('show', SCOPE_BASE + ':' + TASK).split('## Organizer verification', 1)[1]
assert Path(TASK).stat().st_size < 120_000
print('Executor roadmap scope is E8-T10; Organizer verification unchanged; report below 120000 bytes')
spec = importlib.util.spec_from_file_location('evidence', 'roadmap/evidence/E8-T10/run-evidence.py')
redactor = importlib.util.module_from_spec(spec)
spec.loader.exec_module(redactor)
for name in ('35-clean-verify.log', '43-clean-verify.log'):
    path = 'roadmap/evidence/E8-T10/' + name
    assert redactor.clean(git('show', SCOPE_BASE + ':' + path)) == Path(path).read_text(), path
print('Historical failed logs differ only by the credential/idempotency redactor; no attempt was replaced')
for path in Path('roadmap/evidence/E8-T10').glob('*.log'):
    text = path.read_text()
    for name, pattern in {
        'JWT': r'eyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+',
        'Stripe secret': r'(?:sk_(?:test|live)_|whsec_)[A-Za-z0-9]{16,}',
        'IBAN': r'\bES\d{22}\b',
        'private key': r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----',
        'untruncated hash': r'\b[a-f0-9]{32,}\b',
    }.items():
        assert not re.search(pattern, text), name + ' in ' + str(path)
    ignored = subprocess.run(['git', 'check-ignore', '-q', str(path)])
    assert ignored.returncode == 1, 'Evidence is ignored: ' + str(path)
print('Evidence logs are publishable, with no complete credential/IBAN/hash patterns')
