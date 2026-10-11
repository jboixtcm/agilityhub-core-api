#!/usr/bin/env python3
"""Capture a task command without overwriting attempts; allow one hour including the host lock."""
import json
import os
from pathlib import Path
import shlex
import signal
import subprocess
import sys
import time

log = Path(sys.argv[1])
command = sys.argv[2:]
started = time.monotonic()
with log.open("x") as output:
    process = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
    try:
        code = process.wait(timeout=3600)
    except (subprocess.TimeoutExpired, KeyboardInterrupt) as failure:
        code = 124 if isinstance(failure, subprocess.TimeoutExpired) else 130
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
        output.write(f"\nEvidence runner stopped the process group: exit {code}\n")
metadata = {"command": shlex.join(command), "exit_code": code, "seconds": round(time.monotonic() - started, 2)}
log.with_suffix(".json").write_text(json.dumps(metadata, indent=2) + "\n")
print(json.dumps(metadata), flush=True)
print("".join(log.read_text(errors="replace").splitlines(keepends=True)[-40:]), end="")
sys.exit(code)
