#!/usr/bin/env python3
"""Append literal final log tails to Round 2 without touching organizer verification."""
from pathlib import Path
import json
import re

base = Path(__file__).parent
records = json.loads((base / 'resume-verification-commands.json').read_text())
for log in base.glob('*.log'):
    prefix = log.name.split('-', 1)[0]
    if not prefix.isdigit() or int(prefix) < 92 or not Path(str(log) + '.exit').exists():
        continue
    output = log.read_text()
    output = re.sub(r'\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', 'eyJ...[truncated]', output)
    output = re.sub(r'\b[0-9a-f]{24,}\b', lambda match: match[0][:8] + '...[truncated]', output)
    log.write_text('\n'.join(line.rstrip() for line in output.splitlines()) + ('\n' if output else ''))
blocks = ['#### Resumed final verification\n',
          'The two clean runs use different recorded random seeds. Each runs through the shared host lock. '
          'Test summaries are read from that run\'s Surefire/Failsafe XML before the next clean build. '
          'Full logs and exit sidecars below are retained for publication. Log whitespace is trimmed; '
          'token/hash-shaped strings are truncated before copying the literal tails.\n']
for record in records:
    log = Path(record['log'])
    result = int(Path(str(log) + '.exit').read_text())
    assert result == 0, f'{log}: exit {result}'
    output = log.read_text()
    output = re.sub(r'\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', 'eyJ...[truncated]', output)
    output = re.sub(r'\b[0-9a-f]{24,}\b', lambda match: match[0][:8] + '...[truncated]', output)
    output = '\n'.join(line.rstrip() for line in output.splitlines()) + ('\n' if output else '')
    log.write_text(output)
    tail = '\n'.join(output.splitlines()[-40:])
    blocks.append(f'**{log.name}**\n\nExact command:\n```sh\n{record["command"]}\n```\n'
                  f'Exit code: `{result}`. Complete output: `{log}`.\n\n'
                  + (f'Literal last {min(40, len(output.splitlines()))} lines:\n```text\n{tail}\n```\n'
                     if output else 'Output is empty (zero lines).\n'))

task = Path('roadmap/tasks/E8-T04.md')
body, organizer = task.read_text().split('\n## Organizer verification\n', 1)
marker = 'Final verification evidence follows below.'
body = body.split(marker, 1)[0] + marker + '\n\n' + '\n'.join(blocks)
result = body.rstrip() + '\n\n## Organizer verification\n' + organizer
assert len(result.encode()) < 120_000, 'Task report exceeds 120 KB'
task.write_text(result)
print(f'Rendered {len(records)} successful verification commands; task size {len(result.encode())} bytes')
