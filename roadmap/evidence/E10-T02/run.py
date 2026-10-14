#!/usr/bin/env python3
"""Retain each command, output and exit independently, with a one-hour deadline."""
import os, pathlib, signal, subprocess, sys, time, shlex
root=pathlib.Path(__file__).resolve().parent
name=sys.argv[1]; args=sys.argv[2:]; prefix=root/name
for suffix in ('.log','.exit','.command.txt'):
    if prefix.with_suffix(suffix).exists(): raise SystemExit('Attempt already exists: '+name)
prefix.with_suffix('.command.txt').write_text(shlex.join(args)+'\n')
prefix.with_suffix('.cwd.txt').write_text(str(pathlib.Path.cwd())+'\n')
start=time.time()
with prefix.with_suffix('.log').open('w') as output:
    proc=subprocess.Popen(args,stdout=output,stderr=subprocess.STDOUT,start_new_session=True)
    while proc.poll() is None and time.time()-start < 3600:
        time.sleep(1)
    if proc.poll() is None:
        output.write('\nEvidence deadline exceeded; no successful completion claimed.\n'); output.flush()
        try:
            os.killpg(proc.pid,signal.SIGTERM)
            proc.wait(timeout=15)
        except ProcessLookupError:
            pass
        except (PermissionError,subprocess.TimeoutExpired) as error:
            output.write(f'Process cleanup incomplete: {type(error).__name__}; inspect writers before another build.\n')
        code=124
    else:
        code=proc.returncode
prefix.with_suffix('.exit').write_text(str(code)+'\n')
print(f'{name}: exit={code}, wall_elapsed={time.time()-start:.1f}s')
print('\n'.join(prefix.with_suffix('.log').read_text(errors='replace').splitlines()[-40:]))
sys.exit(code)
