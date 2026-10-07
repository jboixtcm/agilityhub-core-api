#!/usr/bin/env python3
"""Render final command tails without changing organizer verification or old evidence."""
import json
from pathlib import Path
import re
base = Path(__file__).parent
records = json.loads((base / 'round3-verification-commands.json').read_text())
for log in base.glob('*.log'):
    number = log.name.split('-', 1)[0]
    if not number.isdigit() or int(number) < 109 or not Path(str(log) + '.exit').exists():
        continue
    body = log.read_text()
    body = re.sub(r'\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', 'eyJ...[truncated]', body)
    body = re.sub(r'\b[0-9a-f]{24,}\b', lambda match: match[0][:8] + '...[truncated]', body)
    log.write_text('\n'.join(line.rstrip() for line in body.splitlines()) + ('\n' if body else ''))
blocks = []
for record in records:
    log = base / record['log']
    result = int(Path(str(log) + '.exit').read_text())
    assert result == 0, f'{log}: {result}'
    lines = log.read_text().splitlines()
    blocks.append(f'**{log.name}**\n\nExact command:\n```sh\n{record["command"]}\n```\n'
                  f'Exit code: `{result}`. Full output: `{log}`.\n\n'
                  + (f'Literal last {min(40, len(lines))} lines:\n```text\n' + '\n'.join(lines[-40:]) + '\n```\n' if lines else 'Output is empty (zero lines).\n'))
p = Path('roadmap/tasks/E8-T04.md')
body, organizer = p.read_text().split('\n## Organizer verification\n', 1)
marker = '##### Final Round 3 verification'
body = body.split(marker, 1)[0].rstrip() + '\n\n' + marker + '\n\n' + '\n'.join(blocks)
result = body.rstrip() + '\n\n## Organizer verification\n' + organizer
assert len(result.encode()) < 120_000, f'Task size {len(result.encode())}'
p.write_text(result)
print(f'Rendered {len(records)} commands; task size {len(result.encode())} bytes')
