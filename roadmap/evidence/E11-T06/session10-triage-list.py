#!/usr/bin/env python3
"""Session 10 survivor triage worklist (E11-T06 step 8).

Applies `pit/merge-current.py`'s rules to the unit pass and the integration supplements of the given packages
(a supplement kill counts only when it ran at least one test; a unit NO_COVERAGE mutant that integration tests
exercised becomes SURVIVED) and lists every mutant that is still not killed, with its source line and the
decision an earlier session recorded for the same class, method and mutator (a hint only: lines moved).

Usage: session10-triage-list.py --package payments --supplement A.xml [--supplement B.xml ...] --output rows.json
"""
import argparse
from collections import defaultdict
from importlib.machinery import SourceFileLoader
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
S8 = SourceFileLoader('session8', str(HERE / 'session8-pit.py')).load_module()
TRIAGE = SourceFileLoader('triage', str(HERE / 'session6-triage.py')).load_module()


def history():
    hints = defaultdict(list)
    for path in sorted((HERE / 'pit').glob('triage-batch*.md')):
        for line in path.read_text().splitlines():
            cells = [cell.strip() for cell in line.split('|')[1:-1]]
            if len(cells) != 5 or ':' not in cells[0] or cells[0].startswith('Class'):
                continue
            clazz, _, number = cells[0].partition(':')
            mutator = cells[2].split(' ')[0]
            hints[(clazz, cells[1], mutator)].append(f'{path.stem} line {number}: {cells[4]}')
    return hints


def source_line(clazz, number):
    top = clazz.split('$')[0].replace('.', '/') + '.java'
    for base in ('src/main/java', 'target/generated-sources/jaxb'):
        path = ROOT / base / top
        if path.exists():
            lines = path.read_text().splitlines()
            return lines[number - 1].strip() if 0 < number <= len(lines) else ''
    return '(source not found)'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--package', choices=S8.PACKAGES, required=True)
    parser.add_argument('--supplement', action='append', required=True,
                        help='a mutations.xml, or a quoted glob relative to pit/session8')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.supplement = [path for pattern in args.supplement
                       for path in (sorted(S8.OUT.glob(pattern)) if '*' in pattern else [Path(pattern)])]
    print('Supplements: ' + ', '.join(path.name for path in args.supplement))
    prefix = S8.PACKAGES[args.package]
    base = {TRIAGE.identity(m): m for m in ET.parse(S8.UNIT_XML).getroot()
            if m.findtext('mutatedClass').startswith(prefix)}
    seen = set()
    for path in args.supplement:
        for mutation in ET.parse(path).getroot():
            key = TRIAGE.identity(mutation)
            assert key in base, f'{path.name}: mutation outside the unit pass ({key})'
            assert key not in seen, f'{path.name}: mutation reported twice'
            seen.add(key)
            tests = int(mutation.get('numberOfTestsRun', '0'))
            old = base[key].get('status')
            if old != 'KILLED' and mutation.get('status') == 'KILLED' and tests > 0:
                base[key] = mutation
            elif old == 'NO_COVERAGE' and mutation.get('status') == 'SURVIVED' and tests > 0:
                base[key] = mutation
    missing = set(base) - seen
    hints = history()
    rows = []
    for key, mutation in base.items():
        if mutation.get('status') == 'KILLED':
            continue
        clazz = mutation.findtext('mutatedClass')
        simple = clazz.rsplit('.', 1)[-1].split('$')[0]
        method = mutation.findtext('mutatedMethod')
        mutator = mutation.findtext('mutator').rsplit('.', 1)[-1]
        mutator = re.sub(r'Mutator$', '', mutator)
        number = int(mutation.findtext('lineNumber'))
        rows.append({'identity': key, 'status': mutation.get('status'), 'class': clazz, 'method': method,
                     'line': number, 'mutator': mutator, 'description': mutation.findtext('description'),
                     'testsRun': int(mutation.get('numberOfTestsRun', '0')),
                     'killingTest': mutation.findtext('killingTest') or '',
                     'source': source_line(clazz, number),
                     'history': hints.get((simple, method, mutator), [])[:2]})
    rows.sort(key=lambda row: (row['class'], row['line'], row['mutator']))
    with args.output.open('x') as output:
        json.dump(rows, output, indent=1, ensure_ascii=False)
        output.write('\n')
    statuses = defaultdict(int)
    for row in rows:
        statuses[row['status']] += 1
    print(f'{args.package}: {len(base)} generated, {len(missing)} without a supplement row, '
          f'{len(rows)} not killed {dict(statuses)}, {sum(1 for r in rows if r["history"])} with a historical hint')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
