#!/usr/bin/env python3
"""Run one check with an immutable log, exact argv and a one-hour deadline.

Heavy commands must explicitly include the host-lock wrapper. Only this invocation's
process group is stopped on interruption or timeout. Never overwrite earlier evidence.
"""
import argparse
import os
from pathlib import Path
import shlex
import signal
import subprocess
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('name')
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command
    if command and command[0] == '--':
        command = command[1:]
    if not command or '/' in args.name:
        parser.error('Provide an evidence basename and a command')
    paths = {suffix: HERE / (args.name + suffix) for suffix in ('.command', '.log', '.exit')}
    if any(path.exists() for path in paths.values()):
        parser.error('Evidence already exists; use a new attempt number')
    paths['.command'].write_text(shlex.join(command) + '\n')
    with paths['.log'].open('x') as log:
        try:
            process = subprocess.Popen(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT,
                                       start_new_session=True)
        except OSError as error:
            log.write(f'Command could not start: {error}\n')
            paths['.exit'].write_text('exit 127\n')
            print(f'{args.name}: exit 127', flush=True)
            return 127
        try:
            result = process.wait(timeout=3600)
        except (subprocess.TimeoutExpired, KeyboardInterrupt) as error:
            result = 124 if isinstance(error, subprocess.TimeoutExpired) else 130
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
            log.write(f'\nEvidence runner stopped its command group; exit {result}.\n')
    paths['.exit'].write_text(f'exit {result}\n')
    print(f'{args.name}: exit {result}', flush=True)
    return result


if __name__ == '__main__':
    sys.exit(main())
