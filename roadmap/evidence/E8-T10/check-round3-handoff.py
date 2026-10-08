#!/usr/bin/env python3
"""Check the round's scope and preserve all prior evidence and organizer text."""
from pathlib import Path
import re
import subprocess

BASE = 'f063fcd'
TASK = 'roadmap/tasks/E8-T10.md'
EVIDENCE = 'roadmap/evidence/E8-T10/'


def git(*args):
    return subprocess.check_output(['git', *args], text=True)


subprocess.run(['python3', 'bin/check-whitespace.py', '--base', 'e2ecd1e'], check=True)
print('Complete task range, working tree and untracked files: whitespace clean')
changed = set(git('diff', '--name-only', BASE).splitlines()) | set(git('ls-files', '--others', '--exclude-standard').splitlines())
for path in changed:
    if path.startswith('roadmap/'):
        assert path in {TASK, 'roadmap/STATUS.md', 'roadmap/MESSAGES.md'} or path.startswith(EVIDENCE), path
assert Path(TASK).read_text().split('## Organizer verification', 1)[1] == git('show', BASE + ':' + TASK).split('## Organizer verification', 1)[1]
assert Path(TASK).stat().st_size < 120_000
assert Path('roadmap/MESSAGES.md').read_text().startswith(git('show', BASE + ':roadmap/MESSAGES.md'))
print('Exactly E8-T10 roadmap scope; organizer text unchanged; messages append-only; report below 120000 bytes')
for path in git('ls-tree', '-r', '--name-only', BASE, EVIDENCE).splitlines():
    if path.endswith('.log'):
        assert Path(path).read_text() == git('show', BASE + ':' + path), path
print('All earlier evidence logs remain byte-identical')
for path in Path(EVIDENCE).glob('*.log'):
    text = path.read_text()
    for name, pattern in {
        'JWT': r'eyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+',
        'Stripe secret': r'(?:sk_(?:test|live)_|whsec_)[A-Za-z0-9]{16,}',
        'IBAN': r'\bES\d{22}\b',
        'private key': r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----',
        'untruncated hash': r'\b[a-f0-9]{32,}\b',
    }.items():
        assert not re.search(pattern, text), name + ' in ' + str(path)
    assert subprocess.run(['git', 'check-ignore', '-q', str(path)]).returncode == 1, path
print('Evidence logs are publishable and credential/hash patterns are truncated')
