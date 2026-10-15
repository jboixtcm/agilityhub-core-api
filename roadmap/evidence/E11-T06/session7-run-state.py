#!/usr/bin/env python3
"""Prove this verification finished and left no Java worker in the checkout."""
from datetime import datetime, timezone
import os
from pathlib import Path
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
print(datetime.now(timezone.utc).isoformat(), flush=True)
result = (HERE / '131-session7-clean-verify.exit').read_text().strip()
assert result == 'exit 1'
print('131 clean verification: ' + result, flush=True)
holders = subprocess.run(['lsof', '-n', str(HERE / '131-session7-clean-verify.log')],
                         capture_output=True, text=True)
assert holders.returncode == 1 and not holders.stdout, 'The completed command log still has a holder'
print('PASS: no process holds the completed verification log.', flush=True)
processes = subprocess.run(['lsof', '-n', '-a', '-d', 'cwd', '+D', str(ROOT)],
                          capture_output=True, text=True)
assert processes.returncode in (0, 1) and not processes.stderr, 'Process inspection failed'
# lsof may return 1 for unmatched selection terms while still returning its
# complete inventory. Require this script as a positive control before using it.
assert any(len(line.split()) > 1 and line.split()[1] == str(os.getpid())
           for line in processes.stdout.splitlines()), 'Process inventory lacks its positive control'
java = [line for line in processes.stdout.splitlines() if line.split()[0] == 'java']
assert not java, 'A Java worker still has this checkout as its working directory'
print('PASS: no Java worker has this checkout as its working directory.', flush=True)
lock = Path('/tmp/agilityhub-heavy.lock/pid')
print('Host lock PID: ' + (lock.read_text().strip() if lock.exists() else 'absent'), flush=True)
assert not (HERE / '127-session6-pit-payments.exit').exists()
print('Attempt 127 still has no exit record; it is not accepted as a completed PIT verification.', flush=True)
