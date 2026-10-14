#!/usr/bin/env python3
"""Map the audit's temporary copies to their original logs, then validate every finding."""
import json
from pathlib import Path
import re
import subprocess
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
rows = json.loads(Path(sys.argv[1]).read_text())
for row in rows:
    name = Path(row['File']).name
    assert re.fullmatch(r'\d+-session6-[\w-]+\.log', name), 'Unexpected audit input'
    assert (HERE / name).is_file()
    row['File'] = str((HERE / name).relative_to(ROOT))
mapped = Path(sys.argv[2])
with mapped.open('x') as output:
    json.dump(rows, output, indent=2)
    output.write('\n')
raise SystemExit(subprocess.run([sys.executable, str(HERE / 'classify-trace-findings.py'), str(mapped)]).returncode)
