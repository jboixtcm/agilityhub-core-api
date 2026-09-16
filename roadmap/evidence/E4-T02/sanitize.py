#!/usr/bin/env python3
"""Redact token/hash material in task evidence while preserving complete log lines."""
from pathlib import Path
import re

folder = Path(__file__).resolve().parent
for path in sorted(folder.glob('*.log')):
    text = path.read_text()
    text = re.sub(r'eyJ[A-Za-z0-9_.-]+', 'eyJ…[truncated]', text)
    text = re.sub(r'\b[0-9a-fA-F]{24,}\b', lambda match: match[0][:6] + '…[truncated]', text)
    text = re.sub(r'(?i)(\b(?:[a-z]*hash|[a-z]*token|password|secret)[\"\s]*[:=][\"\s]*)([A-Za-z0-9_+/.$=-]{12,})',
                  lambda match: match[1] + match[2][:6] + '…[truncated]', text)
    text = '\n'.join(line.rstrip() for line in text.split('\n'))
    path.write_text(text)
print('Sanitized all task log files: tokens and hashes truncated.')
