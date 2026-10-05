#!/usr/bin/env python3
"""Capture one verification without overwriting evidence; terminate its process group after one hour."""
import os
from pathlib import Path
import signal
import subprocess
import sys

log = Path(sys.argv[1])
with log.open('x') as output:
    process = subprocess.Popen(sys.argv[2:], stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
    try:
        result = process.wait(timeout=3600)
    except (subprocess.TimeoutExpired, KeyboardInterrupt):
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=20)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
        result = 124
Path(str(log) + '.exit').write_text(str(result) + '\n')
print(f'{log}: exit {result}', flush=True)
sys.exit(result)
