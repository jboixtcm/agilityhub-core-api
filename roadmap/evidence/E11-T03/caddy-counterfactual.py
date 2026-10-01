#!/usr/bin/env python3
"""Prove the live SPA-header check catches a missing Caddy baseline."""
from pathlib import Path
import subprocess
ROOT = Path(__file__).resolve().parents[3]
path = ROOT / 'deploy/Caddyfile'
original = path.read_text()
assert '        X-Content-Type-Options nosniff\n' in original
try:
    path.write_text(original.replace('        X-Content-Type-Options nosniff\n', ''))
    command = ['bin/deploy-smoke','--image-tag','e11-security-local','--security-only']
    print('COUNTERFACTUAL COMMAND ' + ' '.join(command),flush=True)
    result = subprocess.run(command,cwd=ROOT,capture_output=True,text=True)
    print(result.stdout, end=''); print(result.stderr,end='')
    print('COUNTERFACTUAL SMOKE EXIT ' + str(result.returncode),flush=True)
    assert result.returncode != 0 and 'PASS branding resolves' in result.stdout and 'AssertionError' in result.stderr, 'Expected a header assertion after healthy API startup'
finally:
    path.write_text(original)
    print('RESTORED Caddy baseline',flush=True)
