#!/usr/bin/env python3
"""E11-T06: totals of target/surefire-reports and target/failsafe-reports, every failing test, and the classes this task adds
or changes (passed as arguments: simple class names)."""
import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verify-exit', type=Path, help='Require a successful clean-verification exit record')
    parser.add_argument('classes', nargs='*')
    args = parser.parse_args()
    wanted, seen = set(args.classes), set()
    problems = 0
    if args.verify_exit:
        result = args.verify_exit.read_text().strip()
        print(f'Clean verification: {result} ({args.verify_exit})')
        if result not in ('exit 0', '0'):
            return 1
    for kind in ('surefire', 'failsafe'):
        totals = dict(tests=0, failures=0, errors=0, skipped=0)
        reports = sorted((ROOT / f'target/{kind}-reports').glob('TEST-*.xml'))
        rows = []
        for report in reports:
            suite = ET.parse(report).getroot()
            for key in totals:
                totals[key] += int(suite.get(key, 0))
            name = suite.get('name').rsplit('.', 1)[-1]
            seen.add(name)
            for case in suite.iter('testcase'):
                for problem in case:
                    if problem.tag in ('failure', 'error', 'flakyFailure', 'flakyError', 'rerunFailure', 'rerunError'):
                        problems += 1
                        message = next(iter((problem.get('message') or '').splitlines()), '')
                        print(f'{kind} {problem.tag.upper()}: {name}.{case.get("name")}: {message[:160]}')
            if name in wanted or any(name.endswith(suffix) for suffix in ('SurvivorsTest', 'Survivors2Test')):
                rows.append(f'  {name}: tests={suite.get("tests")} failures={suite.get("failures")} errors={suite.get("errors")} skipped={suite.get("skipped")}')
        print(f'{kind}: {len(reports)} classes, ' + ', '.join(f'{key}={value}' for key, value in totals.items()))
        for row in rows:
            print(row)
        if args.verify_exit and (not reports or totals['failures'] or totals['errors'] or totals['skipped']):
            problems += 1
    if args.verify_exit and wanted - seen:
        print('Missing requested test classes: ' + ', '.join(sorted(wanted - seen)))
        problems += 1
    return 1 if args.verify_exit and problems else 0


if __name__ == '__main__':
    sys.exit(main())
