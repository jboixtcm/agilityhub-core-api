#!/usr/bin/env python3
"""Read-only scope, report and evidence checks for this executor session."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import sys

root = Path(__file__).resolve().parents[3]
evidence = Path(__file__).resolve().parent
task = root / 'roadmap/tasks/E8-T09.md'

def git(*args):
    return subprocess.check_output(['git', *args], cwd=root, text=True)

base = sys.argv[1] if len(sys.argv) > 1 else 'HEAD'
changed = git('diff', base, '--name-only').splitlines()
external = json.loads(Path(sys.argv[2]).read_text()) if len(sys.argv) > 2 else {}
if external:
    # E96 organizer work appeared during the long clean run. Preserve these exact
    # bytes; this exception never admits code, ROADMAP or this task's verification.
    assert set(external) == {'docs/DECISIONS_PENDENTS.md', 'roadmap/tasks/E11-T06.md',
                             'roadmap/tasks/E8-T10.md', 'roadmap/tasks/E9-T01.md'}
    for path, digest in external.items():
        assert hashlib.sha256((root / path).read_bytes()).hexdigest() == digest, f'Concurrent file changed: {path}'
    print('E96 concurrent organizer files preserved byte-for-byte: ' + ', '.join(sorted(external)))
owned = [path for path in changed if path not in external]
if external:
    allowed = {'CHANGELOG.md', 'roadmap/STATUS.md', 'roadmap/MESSAGES.md', 'roadmap/tasks/E8-T09.md',
               'bin/security-secret-policy-test.py',
               'src/main/java/com/agilityhub/core/payments/application/PaymentNotSubmitted.java',
               'src/main/java/com/agilityhub/core/payments/application/PaymentRefunds.java',
               'src/main/java/com/agilityhub/core/payments/application/stripe/StripeCalls.java',
               'src/main/java/com/agilityhub/core/payments/persistence/UpfrontPaymentRepository.java',
               'src/test/java/com/agilityhub/core/payments/api/CardPaymentsIT.java',
               'src/test/java/com/agilityhub/core/payments/application/stripe/StripeHttpFixture.java'}
    assert all(path in allowed or path.startswith('roadmap/evidence/E8-T09/') for path in owned)
assert [p for p in owned if p.startswith('roadmap/tasks/')] == ['roadmap/tasks/E8-T09.md']
assert 'roadmap/ROADMAP.md' not in changed
original = git('show', base + ':roadmap/tasks/E8-T09.md')
assert task.read_text().split('## Organizer verification', 1)[1] == original.split('## Organizer verification', 1)[1]
assert (root / 'roadmap/MESSAGES.md').read_text().startswith(git('show', base + ':roadmap/MESSAGES.md'))
assert task.stat().st_size < 120 * 1024
subprocess.run(['git', 'diff', '--check', base, '--', *owned], cwd=root, check=True)
print('Scope: only E8-T09 task; ROADMAP and Organizer verification unchanged; MESSAGES append-only.')
print('git diff --check (complete round from supplied base): exit 0')

untracked = set(git('ls-files', '--others', '--exclude-standard').splitlines())
files = {root / p for p in changed + sorted(untracked) if p not in external} | {p for p in evidence.iterdir() if p.is_file()}
files.add(root / 'src/main/java/com/agilityhub/core/payments/application/PaymentNotSubmitted.java')
for path in files:
    text = path.read_text()
    # git diff --check above covers added tracked lines; historical MESSAGES whitespace is outside this task.
    if path.parent == evidence or str(path.relative_to(root)) in untracked:
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
