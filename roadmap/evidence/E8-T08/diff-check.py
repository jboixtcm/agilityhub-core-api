#!/usr/bin/env python3
"""E8-T08 round 2 (E92 point 2): `git diff --check` over everything this session will publish, untracked files included.

Tracked changes: `git diff --check HEAD`. Untracked (new) files: `git diff --no-index --check /dev/null <file>`, which
reports the same whitespace errors for a file's added lines. Read-only: no index or ref is written. Exit 0 only when git
reports nothing for every file."""
import subprocess
import sys


def run(*args):
    return subprocess.run(['git', *args], capture_output=True, text=True)


tracked = run('diff', '--check', 'HEAD')
problems = tracked.stdout.strip()
print('git diff --check HEAD -> exit %d' % tracked.returncode)
untracked = run('ls-files', '--others', '--exclude-standard').stdout.split()
for name in untracked:
    result = run('diff', '--no-index', '--check', '/dev/null', name)
    if result.stdout.strip():
        problems += '\n' + result.stdout.strip()
print('untracked files checked: %d' % len(untracked))
changed = run('diff', '--name-only', 'HEAD').stdout.split()
print('tracked files changed: %d' % len(changed))
if problems.strip():
    print(problems)
    sys.exit(2)
print('no whitespace errors')
