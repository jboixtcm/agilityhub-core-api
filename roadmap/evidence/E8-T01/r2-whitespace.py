#!/usr/bin/env python3
"""E8-T01 round 2, review #6: the whitespace check covers everything this task delivered.

  strip   remove trailing whitespace (and blank lines at the end) from roadmap/evidence/E8-T01/*.log; meaning unchanged
  check   `git diff --check fb249ea^ -- <every tracked path E8-T01 added or changed in round 1 or round 2>` and, for the files
          this round adds (untracked until the publish commit), git's own rules applied to their bytes; prints the commands
Read-only git only (`git show --name-only`, `git status --porcelain`, `git diff --check`).
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
BASE = 'fb249ea'
# Round 1's publish commit also carried the organizer's edits of the 01-10 morning (named in the round-1 report), not E8-T01's.
NOT_E8_T01 = {'docs/DECISIONS_PENDENTS.md', 'docs/specs/S16-recorreguts-rings-i-muntatge.md', 'roadmap/tasks/E7-T04.md', 'roadmap/tasks/E7-T06.md'}
# Files other sessions changed in this working tree during round 2 (not E8-T01's; left untouched).
OTHERS_NOW = {'docs/DECISIONS_PENDENTS.md', 'docs/specs/S12-facturacio-i-pagaments.md', 'docs/specs/S15-processos-programats.md',
              'roadmap/tasks/E8-T02.md', 'roadmap/tasks/E8-T06.md'}


def git(*args):
    return subprocess.run(['git', *args], cwd=ROOT, capture_output=True, text=True)


def strip():
    for log in sorted((ROOT / 'roadmap/evidence/E8-T01').glob('*.log')):
        text = log.read_text(errors='surrogateescape')
        lines = [re.sub(r'[ \t\r\f\v]+$', '', line) for line in text.split('\n')]
        cleaned = '\n'.join(lines).rstrip('\n')
        cleaned = cleaned + '\n' if cleaned else ''
        if cleaned != text:
            log.write_text(cleaned, errors='surrogateescape')
            print('stripped', log.relative_to(ROOT))


def problems(path):
    """git's default whitespace rules on a whole new file: trailing whitespace, space before tab in the indent, blank line at EOF."""
    found = []
    lines = (ROOT / path).read_text(errors='surrogateescape').split('\n')
    for number, line in enumerate(lines, 1):
        if re.search(r'[ \t\r]+$', line):
            found.append(f'{path}:{number}: trailing whitespace.')
        if re.match(r'^\t* +\t', line):
            found.append(f'{path}:{number}: space before tab in indent.')
    if len(lines) > 1 and lines[-1] == '' and lines[-2].strip() == '':
        found.append(f'{path}: new blank line at EOF.')
    return found


def check():
    round1 = [p for p in git('show', '--name-only', '--format=', BASE).stdout.split('\n') if p and p not in NOT_E8_T01]
    status = [line for line in git('status', '--porcelain', '--untracked-files=all').stdout.split('\n') if line]
    round2 = [line[3:] for line in status if line[3:] not in OTHERS_NOW and not line[3:].startswith('target/')]
    untracked = sorted(line[3:] for line in status if line.startswith('??') and line[3:] in round2)
    tracked = sorted({p for p in round1 + round2 if p not in untracked and (ROOT / p).exists()})
    print(f'{len(round1)} round-1 paths of E8-T01, {len(round2)} round-2 paths ({len(untracked)} new), {len(tracked)} tracked paths checked')
    command = ['git', 'diff', '--check', f'{BASE}^', '--', *tracked]
    print('$ git diff --check ' + BASE + '^ -- <' + str(len(tracked)) + ' paths: ' + ' '.join(tracked) + '>')
    result = git(*command[1:])
    print(result.stdout + result.stderr, end='')
    print(f'exit {result.returncode}')
    found = [problem for path in untracked for problem in problems(path)]
    print(f'$ new files ({len(untracked)}): ' + ' '.join(untracked))
    print('\n'.join(found) if found else '(no whitespace problem)')
    print(f'exit {1 if found else 0}')
    return result.returncode or (1 if found else 0)


if __name__ == '__main__':
    if sys.argv[1] == 'strip':
        strip()
    else:
        sys.exit(check())
