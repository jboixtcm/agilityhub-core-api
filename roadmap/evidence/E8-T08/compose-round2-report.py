#!/usr/bin/env python3
"""E8-T08 round 2: inserts «### Round 2 report» (with the literal log tails) before the Organizer verification section."""
from pathlib import Path

E = Path('roadmap/evidence/E8-T08')


def tail(name, n=40):
    lines = (E / name).read_text().split('\n')
    if lines and lines[-1] == '':
        lines.pop()
    return '\n'.join(lines[-n:]) if lines else '(no output)'


audit = (E / '32-dependency-audit.log').read_text()
head = audit[audit.index('{'):audit.index('"secretRules"')].rstrip()
template = (E / 'round2-report.md.in').read_text()
for name in ('29-clean-verify.log', '30-test-summary.log', '31-scan-policy-test.log', '32-dependency-audit.log',
             '34-openapi-first.log', '35-openapi-second.log', '36-normalize.log', '37-diff-check.log'):
    template = template.replace('{{tail:' + name + '}}', tail(name))
report = template.replace('{{audit-head}}', head)
assert '{{' not in report.replace('{{slug}}', '').replace('{{number}}', '').replace('{{n}}', ''), 'unfilled placeholder'
report = report.replace('{{slug}}', '{slug}').replace('{{number}}', '{number}').replace('{{n}}', '{n}')
path = Path('roadmap/tasks/E8-T08.md')
text = path.read_text()
marker = '\n## Organizer verification\n'
assert text.count(marker) == 1 and '\n### Round 2 report\n' not in text
path.write_text(text.replace(marker, '\n' + report.rstrip('\n') + '\n' + marker))
print(len(path.read_bytes()))
