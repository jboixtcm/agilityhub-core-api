#!/usr/bin/env python3
"""Read-only final scope checks for the E11-T06 blocked handoff."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[3]


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT, text=True)


task = 'roadmap/tasks/E11-T06.md'
current = (ROOT / task).read_text()
original = git('show', 'HEAD:' + task)
marker = '\n## Organizer verification\n'
assert current.rsplit(marker, 1)[1] == original.rsplit(marker, 1)[1]
assert (ROOT / task).stat().st_size < 120000
assert 'status: blocked' in current.split('---', 2)[1]
allowed = {task, 'roadmap/STATUS.md', 'roadmap/MESSAGES.md', 'CHANGELOG.md'}
changed = git('diff', '--name-only').splitlines()
new = git('ls-files', '--others', '--exclude-standard').splitlines()
assert all(path in allowed or path.startswith('roadmap/evidence/E11-T06/') for path in changed + new)
assert (ROOT / 'roadmap/MESSAGES.md').read_text().startswith(git('show', 'HEAD:roadmap/MESSAGES.md'))
logs = sorted((ROOT / 'roadmap/evidence/E11-T06').glob('1[34][0-9]-session7-*.log'))
for log in logs:
    result = subprocess.run(['git', 'check-ignore', '--no-index', '-q', str(log.relative_to(ROOT))], cwd=ROOT)
    assert result.returncode == 1, 'Evidence log ignored: ' + log.name
print('PASS: E11-T06 blocked; task under 120 KB; Organizer verification unchanged.')
print('PASS: MESSAGES append-only; only this task, its evidence, generated STATUS and CHANGELOG changed.')
print(f'PASS: {len(logs)} session-7 evidence logs remain publishable; no ignore rule added.')
print('No product, Java test, API snapshot, catalog, other task or ROADMAP changes.')
