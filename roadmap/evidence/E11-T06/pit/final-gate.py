#!/usr/bin/env python3
"""E11-T06: the full-package gate over the final batch reports, refusing an incomplete set of batches.

Usage: final-gate.py OUTPUT_DIR REPORT.xml... — checks that every batch of `batches.py` (which partitions every top-level class
of payments, clubs.bookings and identity) has at least one mutation in the given reports (a missing batch is refused; a batch
report is complete by construction, one PIT run per batch), then runs `bin/mutation-gate` with the thresholds of the
report's Assumption 1; `bin/mutation-gate` refuses a class outside the three packages and a mutation reported twice, and
does not overwrite an existing OUTPUT_DIR/summary.json.
"""
from importlib.machinery import SourceFileLoader
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
THRESHOLDS = {'bookings': '70', 'identity': '75', 'payments': '80'}


def main():
    output, reports = sys.argv[1], [Path(arg) for arg in sys.argv[2:]]
    batches = SourceFileLoader('batches', str(HERE / 'batches.py')).load_module().batches()
    classes = {m.findtext('mutatedClass') for report in reports for m in ET.parse(report).getroot()}
    owners = SourceFileLoader('owner', str(HERE / 'batches.py')).load_module().owners
    missing = [batch for batch, globs in batches.items()
               if not any(owners(clazz.split('$')[0], {batch: globs}) for clazz in classes)]
    if missing:
        sys.exit('Batches without any mutation in the given reports: ' + ', '.join(missing))
    print(f'All {len(batches)} batches are present ({len(classes)} mutated classes).', flush=True)
    command = [sys.executable, str(ROOT / 'bin/mutation-gate'), '--output', output]
    for report in reports:
        command += ['--xml', str(report)]
    for package, minimum in THRESHOLDS.items():
        command += ['--minimum-' + package, minimum]
    return subprocess.run(command).returncode


if __name__ == '__main__':
    sys.exit(main())
