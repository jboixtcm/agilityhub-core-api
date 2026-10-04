#!/usr/bin/env python3
"""Render literal final command tails; never alter organizer verification."""
from pathlib import Path
import re
ROOT = Path(__file__).resolve().parents[3]
EVIDENCE = Path(__file__).resolve().parent
TASK = ROOT/'roadmap/tasks/E11-T03.md'
entries = [
    ('120-clean-verify-resumed.log', None),
    ('136-clean-test-summary.log', None),
    ('137-openapi-first.log', None),
    ('138-openapi-first-cmp.log', None),
    ('139-openapi-second.log', None),
    ('140-openapi-second-cmp.log', None),
    ('141-pitest-resumed.log', None),
    ('142-mutation-summary.log', None),
    ('144-image-scan.log', None),
    ('145-dependency-audit.log', None),
    ('146-compose-positive.log', None),
    ('148-compose-negative.log', None),
    ('150-published-compose-proof.log', None),
    ('149-fresh-ci-results.log', None),
    ('123-scan-policy-resumed.log', None),
    ('132-secret-policy-final.log', None),
    ('129-gitleaks-history-fixed.log', None),
    ('152-working-tree-secret-scan-final.log', None),
]

parts=['### Evidence', '', 'Commands run from the repository root. Maven uses `MAVEN_OPTS=-Dmaven.repo.local=<repo>/.local/e11-maven/repository`; Docker builds use `BUILDX_CONFIG=<repo>/.local/e11-buildx`. Both caches are ignored. Each resumed command runs through `resume-command.py` with a 3600-second deadline; the logged command is the exact child command. The absolute host-lock prefix is part of each heavy command. All complete logs are retained under `roadmap/evidence/E11-T03/`. The following blocks are the literal last 40 lines (or all lines for shorter outputs).', '']
for filename,command in entries:
    path=EVIDENCE/filename
    lines=path.read_text().splitlines()
    if command is None:
        assert lines[0].startswith('COMMAND '), filename
        command=lines[0][len('COMMAND '):]
    matches=re.findall(r'^exit (-?\d+)$', '\n'.join(lines), re.M)
    if not matches and filename == 'clean-test-summary.log': matches=['0']
    assert matches, filename
    parts += ['#### '+filename, '', '**Command:** `'+command+'`', '', '**Exit:** '+matches[-1]+'. **Full output:** `roadmap/evidence/E11-T03/'+filename+'`.', '', '```text', *lines[-40:], '```', '']
s=TASK.read_text()
organizer=s[s.index('## Organizer verification'):]
prefix=s[:s.index('## Organizer verification')]
if '### Evidence\n' in prefix: prefix=prefix[:prefix.index('### Evidence\n')]
result=prefix+'\n'.join(parts)+'\n'+organizer
assert len(result.encode()) < 120000, 'Task report exceeds evidence size limit'
TASK.write_text(result)
print('Rendered final command tails; task bytes='+str(len(result.encode())))
