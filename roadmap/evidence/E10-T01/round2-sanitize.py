#!/usr/bin/env python3
"""Retain every log line while truncating synthetic trace ids, recipient hashes and container/database ids."""
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parent
for name in sys.argv[1:]:
    path = root / name
    assert path.parent == root and path.suffix == '.log'
    text = path.read_text()
    lines = text.count('\n')
    text, traces = re.subn(r'(?P<prefix>traceId[=:]\s*)[A-Za-z0-9_-]{8,}',r'\g<prefix>[truncated]',text)
    text, json_traces = re.subn(r'(?P<prefix>"traceId"\s*:\s*")[^"\n]+',r'\g<prefix>[truncated]',text)
    text, hashes = re.subn(r'(?P<prefix>recipientHash=)[A-Za-z0-9_-]+',r'\g<prefix>[truncated]',text)
    text, identifiers = re.subn(r'(?<![A-Za-z0-9])[0-9a-f]{24,64}(?![A-Za-z0-9])',lambda m:m[0][:8]+'[truncated]',text)
    assert text.count('\n') == lines
    path.write_text(text)
    print(f'{name}: truncated {traces+json_traces} trace ids, {hashes} recipient hashes, {identifiers} container/database ids; all lines retained.')
