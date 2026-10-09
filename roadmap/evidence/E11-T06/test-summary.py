#!/usr/bin/env python3
"""E11-T06: totals of target/surefire-reports and target/failsafe-reports, every failing test, and the classes this task adds
or changes (passed as arguments: simple class names)."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]


def main():
    wanted = set(sys.argv[1:])
    for kind in ('surefire', 'failsafe'):
        totals = dict(tests=0, failures=0, errors=0, skipped=0)
        reports = sorted((ROOT / f'target/{kind}-reports').glob('TEST-*.xml'))
        rows = []
        for report in reports:
            suite = ET.parse(report).getroot()
            for key in totals:
                totals[key] += int(suite.get(key, 0))
            name = suite.get('name').rsplit('.', 1)[-1]
            for case in suite.iter('testcase'):
                for problem in case:
                    if problem.tag in ('failure', 'error', 'flakyFailure', 'flakyError', 'rerunFailure', 'rerunError'):
                        message = next(iter((problem.get('message') or '').splitlines()), '')
                        print(f'{kind} {problem.tag.upper()}: {name}.{case.get("name")}: {message[:160]}')
            if name in wanted or any(name.endswith(suffix) for suffix in ('SurvivorsTest', 'Survivors2Test')):
                rows.append(f'  {name}: tests={suite.get("tests")} failures={suite.get("failures")} errors={suite.get("errors")} skipped={suite.get("skipped")}')
        print(f'{kind}: {len(reports)} classes, ' + ', '.join(f'{key}={value}' for key, value in totals.items()))
        for row in rows:
            print(row)


if __name__ == '__main__':
    main()
