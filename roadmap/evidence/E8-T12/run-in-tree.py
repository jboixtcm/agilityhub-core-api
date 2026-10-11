#!/usr/bin/env python3
"""Capture one immutable verification attempt, with a one-hour host-lock/run budget."""
from pathlib import Path
import os
import signal
import shlex
import subprocess
import sys

stem = Path(__file__).resolve().parent / sys.argv[1]
working = Path(sys.argv[2]).resolve()
args = sys.argv[3:]
assert args and not any(stem.with_suffix(ext).exists() for ext in ('.log', '.command', '.exit')), 'Attempt already exists'
stem.with_suffix('.command').write_text('# Working directory: ' + str(working) + '\n' + shlex.join(args) + ' > ' + str(stem.with_suffix('.log')) + ' 2>&1\n')
with stem.with_suffix('.log').open('w') as log:
    process = subprocess.Popen(args, cwd=working, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    try:
        code = process.wait(timeout=3600)
    except (subprocess.TimeoutExpired, KeyboardInterrupt) as error:
        # Stop this attempt's whole process group so no Maven fork keeps writing target/.
        code = 124 if isinstance(error, subprocess.TimeoutExpired) else 130
        log.write('\nVerification exceeded the 3600000 ms budget.\n' if code == 124 else '\nVerification interrupted.\n')
        log.flush()
        try:
            os.killpg(process.pid, signal.SIGTERM)
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
        except ProcessLookupError:
            process.wait()
        finally:
            # The wrapper may exit before its descendants. Reap any remaining group members.
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
stem.with_suffix('.exit').write_text(str(code) + '\n')
print('exit', code)
print('\n'.join(stem.with_suffix('.log').read_text().splitlines()[-40:]))
sys.exit(code)
