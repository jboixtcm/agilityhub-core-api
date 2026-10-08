#!/usr/bin/env python3
"""Capture one bounded command, retaining every attempt and sanitizing its evidence."""
from pathlib import Path
import re
import shlex
import subprocess
import sys

base = Path(__file__).resolve().parent / sys.argv[1]
command = sys.argv[2:]
assert not base.with_suffix('.log').exists(), 'Never overwrite evidence'
base.with_suffix('.command').write_text(shlex.join(command) + '\n')
with base.with_suffix('.log').open('w') as output:
    try:
        result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, timeout=3600)
        code = result.returncode
    except subprocess.TimeoutExpired:
        output.write('\nCommand exceeded 3600 seconds.\n')
        code = 124
text = base.with_suffix('.log').read_text()
text = re.sub(r'(?:sk|rk)_(?:test|live)_[A-Za-z0-9_]+|whsec_[A-Za-z0-9_]+', '[provider-token-truncated]', text)
text = re.sub(r'eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', 'eyJ...[truncated]', text)
text = re.sub(r'\b[0-9a-fA-F]{24,}\b', lambda m: m[0][:8] + '...[truncated]', text)
text = '\n'.join(line.rstrip() for line in text.splitlines()) + ('\n' if text else '')
base.with_suffix('.log').write_text(text)
base.with_suffix('.exit').write_text(str(code) + '\n')
print('\n'.join(text.splitlines()[-40:]))
print(f'Exit: {code}; evidence: {base.name}.log')
sys.exit(code)
