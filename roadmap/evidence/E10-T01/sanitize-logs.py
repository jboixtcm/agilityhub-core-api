#!/usr/bin/env python3
"""Truncate synthetic trace identifiers and recipient hashes in retained test output."""
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parent
for name in sys.argv[1:]:
    path = root / name
    assert path.parent == root and path.suffix == ".log"
    text = path.read_text()
    text, traces = re.subn(
        r'(?P<prefix>traceId[=:]\s*)[A-Za-z0-9_-]{8,}',
        r'\g<prefix>[truncated]', text)
    text, json_traces = re.subn(
        r'(?P<prefix>"traceId"\s*:\s*")[^"\n]+',
        r'\g<prefix>[truncated]', text)
    text, hashes = re.subn(
        r'(?P<prefix>recipientHash=)[A-Za-z0-9_-]+',
        r'\g<prefix>[truncated]', text)
    path.write_text(text)
    print(f"{name}: truncated {traces + json_traces} trace identifiers and {hashes} recipient hashes; all output lines retained.")
