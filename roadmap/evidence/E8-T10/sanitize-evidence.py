#!/usr/bin/env python3
"""Apply the evidence redactor to retained task logs without replacing any run or exit."""
from pathlib import Path
import importlib.util
import sys

folder = Path(__file__).parent
spec = importlib.util.spec_from_file_location('evidence', folder / 'run-evidence.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
for path in sorted(folder.glob('*.log')):
    if path.name in sys.argv[1:]:
        continue
    old = path.read_text()
    new = module.clean(old)
    if old != new:
        assert len(old.splitlines()) == len(new.splitlines()), path
        assert old.splitlines()[-1] == new.splitlines()[-1], path
        path.write_text(new)
        print(path.name + ': redacted fixture credentials and random idempotency keys; lines and exit preserved')
