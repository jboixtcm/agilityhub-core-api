#!/usr/bin/env python3
"""Retain each verification attempt and bound the complete host-lock command to one hour."""
import os
from pathlib import Path
import shlex
import signal
import subprocess
import sys
import time

name, *command = sys.argv[1:]
if command and command[0] == "--":
    command = command[1:]
root = Path(__file__).resolve().parent
log = root / (name + ".log")
metadata = root / (name + ".command.txt")
if log.exists() or metadata.exists():
    raise SystemExit("Refusing to overwrite an earlier attempt")
metadata.write_text(shlex.join(command) + "\n")
started = time.monotonic()
with log.open("w") as output:
    process = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
    try:
        result = process.wait(timeout=3600)
    except subprocess.TimeoutExpired:
        output.write("\nEvidence runner: complete command exceeded 3600 seconds; terminating its process group.\n")
        output.flush()
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=20)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=10)
        result = 124
(root / (name + ".exit")).write_text(str(result) + "\n")
print(f"{name}: exit {result}; elapsed {time.monotonic()-started:.1f}s; log {log}")
print("\n".join(log.read_text(errors="replace").splitlines()[-40:]))
raise SystemExit(result)
