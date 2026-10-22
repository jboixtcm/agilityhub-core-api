#!/usr/bin/env python3
"""E11-T12: insert into the task report the literal last 40 lines of the main Verification commands' logs (those that fit
under the 120 KB task-file limit), taken from 00-tails.md (tails.py). Idempotent: replaces its own block if present.
Run from the repository root: python3 roadmap/evidence/E11-T12/report_tails.py
"""
from pathlib import Path
import re

HERE = Path(__file__).resolve().parent
TASK = HERE.parents[2] / 'roadmap/tasks/E11-T12.md'
LOGS = ['01-clean-verify.log', '04-01-e1-smoke.log', '04-02-e3-smoke.log', '04-03-e3-signup-smoke.log', '04-04-e4-smoke.log',
        '04-05-e5-smoke.log', '04-06-e6-smoke.log', '04-07-e7-smoke.log', '04-08-e8-smoke.log', '04-09-e9-smoke.log',
        '04-10-e10-smoke.log', '04-11-test-e8-smoke.log', '04-11b-test-e8-smoke-python.log', '04-12-oidc-smoke.log',
        '08-security-inventory.log', '09-image-build.log', '09b-image-scan.log', '09c-deploy-smoke.log',
        '09d-security-scan-test.log', '09e-security-scan-rootfs.log']
START, END = '<!-- E11-T12 literal tails: start -->', '<!-- E11-T12 literal tails: end -->'


def main():
    tails = (HERE / '00-tails.md').read_text()
    sections = {m[1]: m[2] for m in re.finditer(r'^## (\S+)\n\n(.*?)(?=^## |\Z)', tails, re.S | re.M)}
    block = [START, '#### Literal last 40 lines (main commands; the rest in `00-tails.md`)', '']
    for log in LOGS:
        block += [f'**`{log}`**', '', sections[log].rstrip(), '']
    block.append(END)
    text = TASK.read_text()
    text = re.sub(re.escape(START) + '.*?' + re.escape(END) + '\n\n', '', text, flags=re.S)
    text = text.replace('\n## Organizer verification', '\n' + '\n'.join(block) + '\n\n## Organizer verification', 1)
    TASK.write_text(text)
    print(f'{TASK.name}: {len(text.encode())} bytes')


if __name__ == '__main__':
    main()
