#!/usr/bin/env python3
"""Append exact commands, exits and literal final tails to the executor's round-two report."""
from pathlib import Path
import sys

root = Path(__file__).resolve().parent
task = Path('roadmap/tasks/E10-T01.md')
text = task.read_text()
executor, organizer = text.split('## Organizer verification', 1)
marker = '#### Round 2 verification evidence\n'
executor = executor.split(marker, 1)[0].rstrip()
blocks = [marker]
for name in sys.argv[1:]:
    command = (root / (name + '.command.txt')).read_text().strip()
    code = (root / (name + '.exit')).read_text().strip()
    log = root / (name + '.log')
    lines = log.read_text().splitlines()
    blocks.append(f'**{name}.**\n\nCommand: `{command}`\n\nExit: `{code}`. Full output: `roadmap/evidence/E10-T01/{name}.log` ({len(lines)} lines).\n\n```text\n'+'\n'.join(lines[-40:])+'\n```\n')
task.write_text(executor+'\n\n'+'\n'.join(blocks)+'\n## Organizer verification'+organizer)
assert task.stat().st_size < 120_000
print(f'Recorded {len(sys.argv)-1} literal evidence tails; task size {task.stat().st_size} bytes.')
