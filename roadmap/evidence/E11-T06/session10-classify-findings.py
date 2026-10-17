#!/usr/bin/env python3
"""Map session10-secret-audit.py's numbered temporary copies to their evidence files, then validate every finding
with classify-trace-findings.py (only the known Springdoc trace-id false positive is accepted)."""
import json
from pathlib import Path
import re
import subprocess
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
rows = json.loads(Path(sys.argv[1]).read_text())
for row in rows:
    name = re.fullmatch(r'\d{4}-(.+)', Path(row['File']).name).group(1)
    candidates = [path for path in (HERE / name, HERE / 'pit/session10' / name, HERE / 'pit/session8' / name)
                  if path.is_file()]
    assert len(candidates) == 1, 'Unexpected audit input: ' + name
    row['File'] = str(candidates[0].relative_to(ROOT))
mapped = Path(sys.argv[2])
with mapped.open('x') as output:
    json.dump(rows, output, indent=2)
    output.write('\n')
raise SystemExit(subprocess.run([sys.executable, str(HERE / 'classify-trace-findings.py'), str(mapped)]).returncode)
