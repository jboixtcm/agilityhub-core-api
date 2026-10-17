#!/usr/bin/env python3
"""Session 11: totals of the surefire/failsafe reports, plus one line per named test class (E11-T06 evidence rule).

Usage: session11-test-summary.py [--only] CLASS...   (simple names; `--only` skips the full totals for a targeted run)
"""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
KEYS = ('tests', 'failures', 'errors', 'skipped')


def suites(directory):
    for path in sorted((ROOT / 'target' / directory).glob('TEST-*.xml')):
        yield path.name[len('TEST-'):-len('.xml')], ET.parse(path).getroot()


def line(root):
    return ', '.join(f'{key}={root.get(key, "0")}' for key in KEYS)


def main(argv):
    only = argv[:1] == ['--only']
    names = set(argv[1:] if only else argv)
    status = 0
    for directory in ('surefire-reports', 'failsafe-reports'):
        rows = list(suites(directory)) if (ROOT / 'target' / directory).is_dir() else []
        if not only:
            totals = {key: sum(int(root.get(key, '0')) for _, root in rows) for key in KEYS}
            print(f'{directory}: {len(rows)} classes; ' + ', '.join(f'{key}={value}' for key, value in totals.items()))
            status |= totals['failures'] + totals['errors'] > 0
        for name, root in rows:
            if name.rsplit('.', 1)[-1] in names:
                tests = ', '.join(case.get('name') for case in root.iter('testcase'))
                print(f'  {name}: {line(root)}; {root.get("timestamp")}; {tests}')
                status |= int(root.get('failures', '0')) + int(root.get('errors', '0')) > 0
    return 1 if status else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
