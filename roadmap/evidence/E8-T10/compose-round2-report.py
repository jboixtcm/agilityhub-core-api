#!/usr/bin/env python3
"""Render literal final evidence tails while preserving the organizer-owned section byte for byte."""
from pathlib import Path

folder = Path('roadmap/evidence/E8-T10')
task = Path('roadmap/tasks/E8-T10.md')
proofs = [
    ('Final clean verification', '43-clean-verify.log', '/Users/jordib/Dropbox/Documents/SOFTWARE_CANIC/05-desenvolupament/roadmap-kit/mac/heavy.sh ./mvnw -q clean verify'),
    ('Fresh XML test summary', '44-test-summary.log', 'python3 roadmap/evidence/E8-T10/summarize-tests.py'),
    ('Final counterfactual and test identifiers', '42-counterfactual.log', 'python3 roadmap/evidence/E8-T10/counterfactual-round2.py'),
    ('Published baseline CI, not the final commit', '41-baseline-ci-final.log', "gh run view 37804668878 --json status,conclusion,url,jobs --jq '{status,conclusion,url,jobs:[.jobs[]|{name,status,conclusion}]}'"),
]
for name, file, command in [
    ('Full-range whitespace, scope and evidence', '45-handoff.log', 'python3 roadmap/evidence/E8-T10/check-handoff.py'),
    ('Roadmap handoff', '46-status.log', 'python3 roadmap/tools/check.py --set E8-T10 awaiting_verification'),
]:
    if (folder / file).exists():
        proofs.append((name, file, command))

blocks = []
for name, file, command in proofs:
    path = folder / file
    lines = path.read_text().splitlines()
    assert lines[-1] == 'exit 0', str(path)
    tail = '\n'.join(lines[-40:])
    blocks.append(f'**{name}.** Command: `{command}`. Exit: `0`. Full output: `{path}`. Literal last {min(40, len(lines))} lines:\n\n```text\n{tail}\n```\n')
blocks.append('**CI outcome:** baseline run [37804668878](https://github.com/jboixtcm/agilityhub-core-api/actions/runs/37804668878) completed with Build and tests and Dependency vulnerability audit successful, Secret scan failed, and image scan/publish skipped. This is an existing baseline failure, not the result of these uncommitted edits. The organizer must check the entire post-publish run, including the secret policy and both image architectures. No final-commit green result is claimed.\n')
report = (folder / 'round2-report.md.in').read_text().replace('@@FINAL_EVIDENCE@@', '\n'.join(blocks))
original = task.read_text()
executor, organizer = original.split('## Organizer verification', 1)
executor = executor.split('### Round 2 report', 1)[0].rstrip() + '\n\n'
task.write_text(executor + report + '## Organizer verification' + organizer)
assert task.stat().st_size < 120_000
assert task.read_text().split('## Organizer verification', 1)[1] == organizer
print('Round 2 report written with literal evidence tails; Organizer verification preserved')
