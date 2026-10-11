#!/usr/bin/env python3
"""Read-only evidence for the inherited PIT writer; never stop or change a process."""
from datetime import datetime, timezone
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
print(datetime.now(timezone.utc).isoformat(), flush=True)
for name in ('pid', 'what'):
    path = Path('/tmp/agilityhub-heavy.lock') / name
    print(f'Host lock {name}: {path.read_text().strip() if path.exists() else "absent"}', flush=True)
command = ['lsof', '-n', '-a', '-p', '64729,65739', '-d', 'cwd,1,2']
print('Command:', ' '.join(command), flush=True)
result = subprocess.run(command, cwd=ROOT, check=False)
print('lsof exit:', result.returncode, flush=True)
report = ROOT / 'target/pit-reports-session3-payments/mutations.xml'
print('Current mutation report:', report.relative_to(ROOT), flush=True)
if report.exists():
    print('Report bytes:', report.stat().st_size, flush=True)
    try:
        mutations = ET.parse(report).getroot()
        print('Complete mutation report:', len(mutations), flush=True)
    except ET.ParseError as error:
        print('Incomplete mutation report:', error, flush=True)
for suffix in ('.log', '.exit'):
    path = Path(__file__).resolve().parent / ('100-pit-payments-integration' + suffix)
    if path.exists():
        print(f'Checkout evidence {path.name}: inode={path.stat().st_ino}, bytes={path.stat().st_size}', flush=True)
    else:
        print(f'Checkout evidence {path.name}: absent', flush=True)
