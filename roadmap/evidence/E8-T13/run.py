"""Retain one immutable verification log and exit, with a one-hour process-group deadline."""
import os
from pathlib import Path
import shlex
import signal
import subprocess
import sys
import time

name, *command = sys.argv[1:]
base = Path(__file__).resolve().parent / name
log = base.with_suffix('.log')
meta = base.with_suffix('.command')
exit_file = base.with_suffix('.exit')
if any(path.exists() for path in (log, meta, exit_file)):
    raise SystemExit('Refusing to overwrite earlier evidence')
meta.write_text(shlex.join(command) + '\n')
started = time.monotonic()
with log.open('x') as output:
    process = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
    try:
        result = process.wait(timeout=3600)
    except (subprocess.TimeoutExpired, KeyboardInterrupt):
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
        result = 124
        output.write('\nEvidence runner: one-hour deadline or interruption; child group stopped.\n')
exit_file.write_text(str(result) + '\n')
print(f'{shlex.join(command)}\nexit {result}; elapsed {time.monotonic() - started:.1f}s; log {log}')
print('\n'.join(log.read_text(errors='replace').splitlines()[-40:]))
raise SystemExit(result)
