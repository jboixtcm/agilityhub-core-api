#!/usr/bin/env python3
"""E8-T10: check complete task scope, report ownership, whitespace and safe evidence."""
from pathlib import Path
import re
import subprocess

BASE = 'e2ecd1e'
SCOPE_BASE = 'e025b4f'
TASK = 'roadmap/tasks/E8-T10.md'


def git(*args):
    return subprocess.check_output(['git', *args], text=True)


subprocess.run(['python3', 'bin/check-whitespace.py', '--base', BASE], check=True)
print('Full task range plus working tree and untracked files: whitespace clean')
changed = set(git('diff', '--name-only', SCOPE_BASE).splitlines()) | set(git('ls-files', '--others', '--exclude-standard').splitlines())
# The organizer added E95 while this long-running session was testing; preserve those external edits.
external = {'roadmap/tasks/E8-T09.md', 'docs/DECISIONS_PENDENTS.md'}
other = 'roadmap/tasks/E8-T09.md'
before = git('show', SCOPE_BASE + ':' + other).split('## Organizer verification', 1)[0]
after = Path(other).read_text().split('## Organizer verification', 1)[0]
assert after.replace('status: changes_requested', 'status: awaiting_verification', 1) == before
print('Concurrent organizer E95 edits preserved in E8-T09 and DECISIONS_PENDENTS; excluded from executor scope')
for path in changed - external:
    if path.startswith('roadmap/'):
        assert path in {TASK, 'roadmap/STATUS.md', 'roadmap/MESSAGES.md'} or path.startswith('roadmap/evidence/E8-T10/'), path
assert 'roadmap/ROADMAP.md' not in changed
assert Path(TASK).read_text().split('## Organizer verification', 1)[1] == git('show', SCOPE_BASE + ':' + TASK).split('## Organizer verification', 1)[1]
assert Path(TASK).stat().st_size < 120_000
print('Executor roadmap scope is E8-T10; Organizer verification unchanged; report below 120000 bytes')
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
