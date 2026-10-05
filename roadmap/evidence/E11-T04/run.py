#!/usr/bin/env python3
"""Capture one bounded command with immutable evidence and its literal tail."""
import os
from pathlib import Path
import shlex
import signal
import subprocess
import sys

stem, *command = sys.argv[1:]
base = Path(__file__).resolve().parent / stem
base.with_suffix('.command').write_text(shlex.join(command) + '\n')
with base.with_suffix('.log').open('x') as log:
    process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    try:
        status = process.wait(timeout=3600)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
        status = 124
base.with_suffix('.exit').write_text(str(status) + '\n')
print(f'COMMAND {shlex.join(command)} -> exit {status}', flush=True)
print('\n'.join(base.with_suffix('.log').read_text().splitlines()[-40:]), flush=True)
sys.exit(status)
