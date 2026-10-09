#!/usr/bin/env python3
"""E11-T06: which tests the kills of PIT reports rest on; flags kills by a test method that also "killed" a mutant the
re-runs showed to survive (a test that fails in PIT's minion without the mutant's help).

Usage: killers.py SUSPECT_METHOD REPORT.xml... — prints, per report, how many kills name SUSPECT_METHOD and which mutants.
"""
from collections import Counter
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def main():
    suspect, reports = sys.argv[1], [Path(arg) for arg in sys.argv[2:]]
    total = 0
    for report in reports:
        kills = [m for m in ET.parse(report).getroot() if m.get('status') == 'KILLED' and suspect in (m.findtext('killingTest') or '')]
        total += len(kills)
        print(f'{report.name}: {len(kills)} kills by {suspect}')
        for mutation in kills:
            print(f"  {mutation.findtext('mutatedClass').rsplit('.', 1)[1]}:{mutation.findtext('lineNumber')} "
                  f"{mutation.findtext('mutator').rsplit('.', 1)[1]} idx={[n.text for n in mutation.iter('index')]}")
    print('total', total)


if __name__ == '__main__':
    main()
