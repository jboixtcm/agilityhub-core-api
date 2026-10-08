#!/usr/bin/env python3
"""Reproduce the old random-fixture failure without storing or printing credentials."""
import argparse
import contextlib
import io
from pathlib import Path
import subprocess
import sys
import types
from unittest.mock import patch

root = Path(__file__).resolve().parents[3]
parser = argparse.ArgumentParser()
parser.add_argument('--gitleaks', default='gitleaks')
args = parser.parse_args()
original = subprocess.check_output(['git', 'show', 'a76ed9c:bin/security-secret-policy-test.py'], cwd=root, text=True)
module = types.ModuleType('old_policy')
module.__file__ = str(root / 'bin/security-secret-policy-test.py')
exec(compile(original, module.__file__, 'exec'), module.__dict__)
# "more" is a built-in Gitleaks stopword, also observed in the random failure in log 60.
# Interleave letters and digits so entropy/grammar cannot explain the missing finding.
value = ''.join(letter + str(index % 10) for index, letter in enumerate('abcdefghijklmnop')) + 'more'
with patch.object(sys, 'argv', ['policy', '--gitleaks', args.gitleaks]), \
        patch.object(module.secrets, 'token_urlsafe', return_value=value), contextlib.redirect_stdout(io.StringIO()):
    try:
        module.main()
    except AssertionError as failure:
        assert str(failure) == 'Every injected secret must remain detected'
    else:
        raise AssertionError('The old random-fixture defect did not reproduce')
print('PASS old policy test fails when its synthetic random value contains a default stopword')
subprocess.run([sys.executable, str(root / 'bin/security-secret-policy-test.py'), '--gitleaks', args.gitleaks], check=True)
print('PASS fixed policy test detects all six exact locations with unchanged scanner policy')
