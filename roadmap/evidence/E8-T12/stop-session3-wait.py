#!/usr/bin/env python3
"""Interrupt only this task's own evidence runner, and only before Maven starts."""
from pathlib import Path
import os
import shlex
import signal
import subprocess

evidence = Path(__file__).resolve().parent
stem = '124-round5-session3-clean-verify'
log = (evidence / (stem + '.log')).read_text()
assert not (evidence / (stem + '.exit')).exists(), 'Run already finished'
assert 'waiting for the host lock' in log
assert all(line.startswith('heavy.sh:') for line in log.splitlines() if line), 'Maven may have started'
assert not Path('/private/tmp/e8-t12-round5-session3-20261015/target').exists(), 'Build has started'
matches = []
for line in subprocess.check_output(['ps', '-axo', 'pid=,args='], text=True).splitlines():
    pid, command = line.strip().split(None, 1)
    args = shlex.split(command)
    if args and 'python' in Path(args[0]).name.lower() and str(evidence / 'run.py') in args and stem in args:
        matches.append(int(pid))
assert len(matches) == 1, f'Expected one task runner, found {len(matches)}'
print('Maven has not started; interrupting only task E8-T12 attempt 124 runner.', flush=True)
try:
    os.kill(matches[0], signal.SIGINT)
except PermissionError:
    print('Sandbox denied signaling the task runner; leave the bounded attempt running.', flush=True)
    raise SystemExit(1)
print('SIGINT delivered; wait for the runner to record its exit and reap its children.')
