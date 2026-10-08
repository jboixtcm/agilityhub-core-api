#!/usr/bin/env python3
"""E8-T08 round 2 (E92 point 2): strip trailing whitespace and trailing blank lines from this task's evidence logs.

Only whitespace at line ends / file end changes; every character of content is kept. Prints the files it rewrote."""
from pathlib import Path

HERE = Path(__file__).resolve().parent
changed = []
for path in sorted(HERE.glob('*.log')):
    original = path.read_text(encoding='utf-8', errors='surrogateescape')
    lines = [line.rstrip(' \t\r\f\v') for line in original.split('\n')]
    while lines and lines[-1] == '':
        lines.pop()
    normalized = '\n'.join(lines) + '\n' if lines else ''
    if normalized != original:
        path.write_text(normalized, encoding='utf-8', errors='surrogateescape')
        changed.append(path.name)
print('normalized: ' + (', '.join(changed) if changed else '(none)'))
