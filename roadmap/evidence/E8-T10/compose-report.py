#!/usr/bin/env python3
"""Attach literal final evidence tails to the E8-T10 report, preserving the organizer's section."""
from pathlib import Path

root = Path('roadmap/evidence/E8-T10')
task = Path('roadmap/tasks/E8-T10.md')
text = task.read_text()
head, organizer = text.split('## Organizer verification', 1)
head = head.split('#### Final evidence', 1)[0]
head = head.replace('Implementation is complete; the lease fixture was corrected after log `16` and final verification is pending.',
                    'All six points are implemented or safely rejected as the review permits. Fresh clean verification passes (log `21`); final-commit CI remains the post-publish handoff.')
head = head.replace('Verification in progress. Retained attempts:', 'Retained attempts (each log preserves its captured exit; none was reused for another run):')
entries = [
    ('Clean Maven verification', '21-clean-verify.log', '/Users/jordib/Dropbox/Documents/SOFTWARE_CANIC/05-desenvolupament/roadmap-kit/mac/heavy.sh ./mvnw -q clean verify'),
    ('Fresh XML test summary', '18-test-summary.log', 'python3 roadmap/evidence/E8-T10/summarize-tests.py'),
    ('Security suppression policy', '13-policy-tests.log', 'python3 bin/security-scan-test.py'),
    ('Whitespace checker regressions', '25-whitespace-tests.log', 'python3 bin/check-whitespace-test.py'),
    ('Published baseline CI query (not final-commit CI)', '17-ci-status.log', "gh run view 37782510997 --json status,conclusion,url,jobs --jq '{status,conclusion,url,jobs:[.jobs[]|{name,status,conclusion}]}'"),
    ('Complete range, scope and evidence check', '26-handoff.log', 'python3 roadmap/evidence/E8-T10/check-handoff.py'),
    ('Roadmap handoff status', '27-status.log', 'python3 roadmap/tools/check.py --set E8-T10 awaiting_verification'),
]
head += '''#### Final evidence

Heavy verification ran through the host lock with a 3,600-second subprocess timeout (`run-evidence.py`). Stored outputs are normalized only for trailing whitespace/ANSI and hash/credential truncation; tails below are literal last lines of those retained logs. The task's fixed base is `e2ecd1e`: the whitespace command covers that base through all task commits and current changes, not only the uncommitted diff.

'''
for title, filename, command in entries:
    data = (root / filename).read_text().splitlines()
    assert data[-1] == 'exit 0', filename
    tail = '\n'.join(data[-40:])
    head += f'**{title}.** Command: `{command}`. Exit: `0`. Complete output: `roadmap/evidence/E8-T10/{filename}`. Literal last {min(40,len(data))} lines:\n\n```text\n{tail}\n```\n\n'
head += '''**CI limitation.** The query succeeded, but the published baseline run itself failed its secret-policy assertion and skipped image scan/publish. These edits are not committed until the external publish script runs after the session. The organizer must check that resulting commit's complete CI run; no final-commit green claim is made.

**Final checklist.** Re-read the entire code/test/tool diff against the six review points and the session checklist. Every original finding has observed red evidence (`01`, `02`, `03`, `06`, `11`); extra lease-fencing cases extend that regression coverage. Integration spies call the real writers and use real Mongo transactions; no invented HTTP error/nullable shape. Tenant boundaries and infrastructure lock ownership are explicit; optimistic request updates use the version read with their values. Month-end uses the club-local JobContext date; no device time. Reset invalidates config after commit. No personal data or source-content fingerprint enters new persistence or evidence. No API snapshot change is required; no catalog or architecture exception was introduced. No other task, Organizer verification section or git state was changed.

'''
task.write_text(head + '## Organizer verification' + organizer)
print('Executor report composed; organizer section preserved')
