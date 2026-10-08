#!/usr/bin/env python3
"""Read-only scope, report and evidence checks for this executor session."""
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parents[3]
evidence = Path(__file__).resolve().parent
task = root / 'roadmap/tasks/E8-T09.md'

def git(*args):
    return subprocess.check_output(['git', *args], cwd=root, text=True)

changed = git('diff', '--name-only').splitlines()
assert [p for p in changed if p.startswith('roadmap/tasks/')] == ['roadmap/tasks/E8-T09.md']
assert 'roadmap/ROADMAP.md' not in changed
original = git('show', 'HEAD:roadmap/tasks/E8-T09.md')
assert task.read_text().split('## Organizer verification', 1)[1] == original.split('## Organizer verification', 1)[1]
assert (root / 'roadmap/MESSAGES.md').read_text().startswith(git('show', 'HEAD:roadmap/MESSAGES.md'))
assert task.stat().st_size < 120 * 1024
subprocess.run(['git', 'diff', '--check'], cwd=root, check=True)
print('Scope: only E8-T09 task; ROADMAP and Organizer verification unchanged; MESSAGES append-only.')
print('git diff --check: exit 0')

files = {root / p for p in changed} | {p for p in evidence.iterdir() if p.is_file()}
files.add(root / 'src/main/java/com/agilityhub/core/payments/application/PaymentNotSubmitted.java')
for path in files:
    text = path.read_text()
    # git diff --check above covers added tracked lines; historical MESSAGES whitespace is outside this task.
    if path.parent == evidence or path.name == 'PaymentNotSubmitted.java':
        assert not re.search(r'[ \t]+$', text, re.MULTILINE), f'Trailing whitespace: {path}'
    if path.suffix == '.log':
        assert not re.search(r'eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', text), f'JWT in {path}'
        assert not re.search(r'(?:sk|rk)_(?:test|live)_[A-Za-z0-9_]+|whsec_[A-Za-z0-9_]+', text), f'Provider token in {path}'
print('Added tracked lines and new task files: no trailing whitespace; evidence contains no JWT or provider tokens.')

completed = list(evidence.glob('*.exit'))
for exit_path in completed:
    log = exit_path.with_suffix('.log')
    command = exit_path.with_suffix('.command')
    assert log.is_file() and command.is_file() and command.read_text().strip()
    int(exit_path.read_text().strip())
    for path in (log, command, exit_path):
        ignored = subprocess.run(['git', 'check-ignore', '-q', '--', str(path)], cwd=root)
        assert ignored.returncode == 1, f'Evidence ignored: {path}'
print(f'Completed evidence records: {len(completed)}; each has command, exit, log and is not ignored.')
print(f'Task report: {task.stat().st_size} bytes, below 120 KiB.')
