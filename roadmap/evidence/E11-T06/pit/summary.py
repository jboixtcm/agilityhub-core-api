#!/usr/bin/env python3
"""E11-T06: per-batch and per-package outcome table of PIT reports (assertion kills over all generated mutations).

Usage: summary.py REPORT.xml... — prints one row per report and one per package; uses bin/mutation-gate's merge (a
mutation reported twice is refused) and scoring.
"""
from collections import Counter
from importlib.machinery import SourceFileLoader
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[4]
gate = SourceFileLoader('gate', str(ROOT / 'bin/mutation-gate')).load_module()
STATUSES = ('KILLED', 'SURVIVED', 'NO_COVERAGE', 'TIMED_OUT', 'RUN_ERROR', 'MEMORY_ERROR', 'NON_VIABLE')


def row(name, counts, reused):
    total = sum(counts.values())
    cells = ' | '.join(str(counts.get(status, 0)) for status in STATUSES)
    return f'| {name} | {total} | {cells} | {reused} | {100 * counts["KILLED"] / total if total else 0:.2f} % |'


def from_history(mutations):
    """Verdicts PIT took from its history file: a covered mutant reported without running any test."""
    return sum(1 for m in mutations if m.get('status') != 'NO_COVERAGE' and m.get('numberOfTestsRun') == '0')


def main():
    paths = [Path(arg) for arg in sys.argv[1:]]
    print('| Report | Generated | ' + ' | '.join(STATUSES) + ' | From history | Killed / generated |')
    print('|---|---:|' + '---:|' * len(STATUSES) + '---:|---:|')
    for path in paths:
        mutations = list(ET.parse(path).getroot())
        print(row(path.name, Counter(m.get('status') for m in mutations), from_history(mutations)))
    packages = {name: [] for name in gate.PACKAGES}
    for mutation in gate.merge(paths):
        clazz = mutation.findtext('mutatedClass')
        packages[next(name for name, prefix in gate.PACKAGES.items() if clazz.startswith(prefix))].append(mutation)
    for name, mutations in packages.items():
        if mutations:
            print(row(f'**{name}** (merged)', Counter(m.get('status') for m in mutations), from_history(mutations)))


if __name__ == '__main__':
    main()
