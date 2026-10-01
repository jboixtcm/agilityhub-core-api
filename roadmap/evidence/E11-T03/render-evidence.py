#!/usr/bin/env python3
"""Render literal final command tails; never alter organizer verification."""
from pathlib import Path
import re
ROOT = Path(__file__).resolve().parents[3]
EVIDENCE = Path(__file__).resolve().parent
TASK = ROOT/'roadmap/tasks/E11-T03.md'
entries = [
 ('105-clean-verify-e85.log', None),
 ('clean-test-summary.log', None),
 ('107-openapi-first-e85.log', None), ('108-openapi-first-e85-cmp.log', None),
 ('109-openapi-second-e85.log', None), ('110-openapi-second-e85-cmp.log', None),
 ('117-pitest-e85.log', None), ('118-mutation-summary-e85.log', None),
 ('112-image-scan-e85.log', None), ('113-dependency-rootfs-e85.log', None),
 ('102-runtime-version-inventory.log', None),
 ('114-compose-positive-e85.log', None), ('50-caddy-counterfactual.log', None), ('116-compose-negative-e85.log', None),
 ('49-published-ci-final-query.log', None), ('104-e85-background-rule-fixed.log', None),
 ('51-scan-inventory-policy.log','python3 bin/security-scan-test.py'),
 ('63-workflow-lint-patched.log','/private/tmp/e11-actionlint/actionlint .github/workflows/ci.yml'),
 ('78-secret-policy-final.log','python3 bin/security-secret-policy-test.py --gitleaks /private/tmp/e11-security-tools/gitleaks'),
 ('77-gitleaks-history-final.log','/private/tmp/e11-security-tools/gitleaks git --no-banner --redact --config .gitleaks.toml .'),
 ('119-working-tree-secret-scan-e85.log','python3 roadmap/evidence/E11-T03/working-tree-secret-scan.py'),
]

parts=['### Evidence', '', 'Commands run from the repository root. Maven uses `MAVEN_OPTS=-Dmaven.repo.local=/private/tmp/e11-maven.BJqwxL/repository`; Docker builds use a writable `BUILDX_CONFIG=/private/tmp/e11-security-tools/buildx`. The absolute host-lock prefix is part of each heavy command. All complete logs are retained under `roadmap/evidence/E11-T03/`. The following blocks are the literal last 40 lines (or all lines for shorter outputs).', '']
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
