#!/usr/bin/env python3
"""Resume E11-T03 with immutable logs and a one-hour bound per command."""
import os
from pathlib import Path
import shlex
import signal
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[3]
EVIDENCE = Path(__file__).resolve().parent
label, *command = sys.argv[1:]
environment = dict(os.environ,
                   MAVEN_OPTS=f'-Dmaven.repo.local={ROOT}/.local/e11-maven/repository',
                   BUILDX_CONFIG=f'{ROOT}/.local/e11-buildx')
start = time.monotonic()
with (EVIDENCE / label).open('x') as output:
    output.write('COMMAND ' + shlex.join(command) + '\n')
    output.flush()
    process = subprocess.Popen(command, cwd=ROOT, env=environment, stdout=output,
                               stderr=subprocess.STDOUT, start_new_session=True)
    try:
        code = process.wait(timeout=3600)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
        code = 124
        output.write('\nOne-hour command deadline exceeded.\n')
    elapsed = time.monotonic() - start
    output.write(f'\nexit {code}\nelapsed_seconds {elapsed:.3f}\n')
print(f'{label}: exit {code}, elapsed {elapsed:.3f}s')
sys.exit(code if code >= 0 else 128 - code)
