#!/usr/bin/env python3
"""Capture one immutable command attempt with a one-hour process-group deadline."""
import datetime
import json
import os
from pathlib import Path
import re
import shlex
import signal
import subprocess
import sys
import threading


def scrub(line):
    line = re.sub(r"eyJ[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+){1,2}", "eyJ...[truncated]", line)
    line = re.sub(r"(?i)(password|secret|token|apiKey)(\s*[=:]\s*)[^,\s}]+", r"\1\2[redacted]", line)
    line = re.sub(r"[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}", "[email redacted]", line)
    return re.sub(r"\b[0-9a-fA-F]{32,}\b", lambda match: match[0][:6] + "...[truncated]", line)


prefix = Path(sys.argv[1])
command = sys.argv[2:]
started = datetime.datetime.now(datetime.timezone.utc).isoformat()
timed_out = threading.Event()
with prefix.with_suffix(".log").open("x") as output:
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               text=True, errors="replace", start_new_session=True)

    def expire():
        timed_out.set()
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            return
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)

    deadline = threading.Timer(3600, expire)
    deadline.start()
    try:
        for line in process.stdout:
            output.write(scrub(line))
            output.flush()
        exit_code = process.wait()
    finally:
        deadline.cancel()
    if timed_out.is_set():
        output.write("Evidence runner: one-hour deadline exceeded; task process group terminated.\n")
        exit_code = 124

metadata = {"command": shlex.join(command), "exit_code": exit_code,
            "started_utc": started,
            "finished_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "timeout_seconds": 3600, "timed_out": timed_out.is_set()}
with prefix.with_suffix(".json").open("x") as output:
    json.dump(metadata, output, indent=2)
    output.write("\n")
print(json.dumps(metadata, indent=2))
print("Last 40 lines:")
print("".join(prefix.with_suffix(".log").read_text().splitlines(keepends=True)[-40:]), end="")
sys.exit(exit_code)
