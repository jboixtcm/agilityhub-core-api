#!/usr/bin/env python3
"""Retain each command, output and exit independently, with a one-hour deadline."""
import pathlib, subprocess, sys, time, shlex
root=pathlib.Path(__file__).resolve().parent
name=sys.argv[1]; args=sys.argv[2:]; prefix=root/name
for suffix in ('.log','.exit','.command.txt'):
    if prefix.with_suffix(suffix).exists(): raise SystemExit('Attempt already exists: '+name)
prefix.with_suffix('.command.txt').write_text(shlex.join(args)+'\n')
start=time.monotonic()
with prefix.with_suffix('.log').open('w') as output:
    proc=subprocess.Popen(args,stdout=output,stderr=subprocess.STDOUT,start_new_session=True)
    try: code=proc.wait(timeout=3600)
    except subprocess.TimeoutExpired:
        output.write('\nEvidence deadline exceeded; no successful completion claimed.\n'); output.flush()
        try: proc.terminate(); proc.wait(timeout=30)
        except (PermissionError,subprocess.TimeoutExpired): pass
        code=124
prefix.with_suffix('.exit').write_text(str(code)+'\n')
print(f'{name}: exit={code}, elapsed={time.monotonic()-start:.1f}s')
print('\n'.join(prefix.with_suffix('.log').read_text(errors='replace').splitlines()[-40:]))
sys.exit(code)
