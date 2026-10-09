#!/usr/bin/env python3
"""Capture one immutable verification attempt, with a one-hour host-lock/run budget."""
from pathlib import Path
import shlex
import subprocess
import sys

stem = Path(__file__).resolve().parent / sys.argv[1]
args = sys.argv[2:]
assert args and not any(stem.with_suffix(ext).exists() for ext in ('.log', '.command', '.exit')), 'Attempt already exists'
stem.with_suffix('.command').write_text(shlex.join(args) + ' > ' + str(stem.relative_to(Path.cwd()).with_suffix('.log')) + ' 2>&1\n')
with stem.with_suffix('.log').open('w') as log:
    try:
        result = subprocess.run(args, stdout=log, stderr=subprocess.STDOUT, timeout=3600)
        code = result.returncode
    except subprocess.TimeoutExpired:
        log.write('\nVerification exceeded the 3600000 ms budget.\n')
        code = 124
stem.with_suffix('.exit').write_text(str(code) + '\n')
print('exit', code)
print('\n'.join(stem.with_suffix('.log').read_text().splitlines()[-40:]))
sys.exit(code)
