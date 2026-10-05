#!/usr/bin/env python3
"""Read-only evidence of the inherited PIT process and its incomplete output."""
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
print('Observed at', datetime.now(timezone.utc).isoformat(), flush=True)
for filename in ('what', 'pid'):
    print('Host lock', filename + ':', (Path('/tmp/agilityhub-heavy.lock') / filename).read_text().strip(), flush=True)
for pid in ('57363', '57369'):
    command = ['lsof', '-p', pid, '-a', '-d', 'cwd,1,2', '-F', 'pcfin']
    print('READ:', ' '.join(command), flush=True)
    subprocess.run(command, check=True)
log = ROOT / 'roadmap/evidence/E11-T03/141-pitest-resumed.log'
print('Checkout log inode:', log.stat().st_ino)
print('Checkout log bytes:', log.stat().st_size)
print('Checkout log has recorded exit:', any(line.startswith('exit ') for line in log.read_text().splitlines()))
source = ROOT / 'target/pit-reports/mutations.xml'
raw = source.read_text()
complete = raw.rstrip().endswith('</mutations>')
end = raw.rfind('</mutation>') + len('</mutation>')
root = ET.fromstring(raw if complete else raw[:end] + '</mutations>')
print('Mutation XML complete:', complete)
print('Complete records in incomplete output:', len(root))
print('Partial statuses:', dict(Counter(item.get('status') for item in root)))
print('No package score inferred from an incomplete run.')
print('No process signalled, lock removed, container stopped or build output changed by this diagnostic.')
