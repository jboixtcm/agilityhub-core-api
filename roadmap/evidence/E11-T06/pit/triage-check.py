#!/usr/bin/env python3
"""E11-T06: every not-killed mutant (all batches) has exactly one triage row, and every `test:` row names a test that exists.

Compares each `batchN-not-killed.tsv` (first-pass survivors) plus every mutant that a later report of the batch
(`rerunN-*.xml`, `final-batchN-*.xml`) shows not killed although the first pass killed it, with the rows of
`triage-batchN*.md` (same class, line and status), and looks up each `Class#method` of a `test:` decision in `src/test/java`.
When the batch has a final report (`final-batchN-mutations.xml`), every mutant still not killed there must have a `reason:` or
`rerun:` row with the same class, line and mutator: a `test:` row whose mutant survives the final run is reported.
"""
from collections import Counter
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


def identity(mutation):
    return tuple(mutation.findtext(name, '') for name in ('mutatedClass', 'mutatedMethod', 'methodDescription', 'lineNumber',
                                                          'mutator', 'description')) + tuple(
        tuple(node.text for node in mutation.iter(tag)) for tag in ('index', 'block'))


def flipped(batch):
    """Mutants killed in the first pass but not in a later report of the same batch (first-pass kills not reproduced)."""
    first = {identity(m): m.get('status') for m in ET.parse(HERE / f'{batch}-mutations.xml').getroot()}
    rows = {}
    for later in sorted(path for path in HERE.glob(f'rerun{batch[5:]}*-mutations.xml') if re.fullmatch(rf'rerun{batch[5:]}[a-z]?(-attempt\d+)?-mutations\.xml', path.name)) + sorted(HERE.glob(f'final-{batch}-*.xml')):
        for mutation in ET.parse(later).getroot():
            if mutation.get('status') != 'KILLED' and first.get(identity(mutation)) == 'KILLED':
                rows[identity(mutation)] = (mutation.findtext('mutatedClass').rsplit('.', 1)[1], mutation.findtext('lineNumber'),
                                            'SURVIVED' if mutation.get('status') == 'SURVIVED' else mutation.get('status'))
    return Counter(rows.values())

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
TESTS = {path.stem: path.read_text() for path in (ROOT / 'src/test/java').rglob('*Test.java')}


def main():
    problems, totals = [], Counter()
    for tsv in sorted(HERE.glob('batch*-not-killed.tsv')):
        batch = tsv.name.split('-')[0]
        expected = Counter()
        for line in tsv.read_text().splitlines()[1:]:
            status, _, clazz, _, number, *_ = line.split('\t')
            expected[(clazz.rsplit('.', 1)[1], number, status)] += 1
        expected += flipped(batch)
        found, reasons = Counter(), Counter()
        for triage in sorted(path for path in HERE.glob(f'triage-{batch}*.md') if re.fullmatch(rf'triage-{batch}[a-z]?\.md', path.name)):
            for row in triage.read_text().splitlines():
                cells = [cell.strip() for cell in row.strip().strip('|').split('|')]
                if len(cells) != 5 or not re.match(r'^[\w$]+:\d+$', cells[0]):
                    continue
                clazz, number = cells[0].split(':')
                found[(clazz, number, cells[3])] += 1
                decision = cells[4]
                kind = decision.split(':', 1)[0]
                totals[kind] += 1
                if kind == 'test':
                    for test_class, method in re.findall(r'(\w+Test)#(\w+)', decision):
                        source = TESTS.get(test_class)
                        if source is None or not re.search(r'\bvoid ' + re.escape(method) + r'\s*\(', source):
                            problems.append(f'{triage.name}: {cells[0]} names a missing test {test_class}#{method}')
                else:
                    reasons[(clazz, number, (re.match(r'[A-Za-z]+', cells[2]) or re.match('', '')).group(0))] += 1
                if kind not in ('test', 'reason', 'rerun'):
                    problems.append(f'{triage.name}: {cells[0]} has no decision')
        final = HERE / f'final-{batch}-mutations.xml'
        if final.exists():
            left = Counter((m.findtext('mutatedClass').rsplit('.', 1)[1], m.findtext('lineNumber'),
                            m.findtext('mutator').rsplit('.', 1)[1].removesuffix('Mutator'))
                           for m in ET.parse(final).getroot() if m.get('status') != 'KILLED')
            for place, count in (left - reasons).items():
                problems.append(f'{final.name}: {count} {place[2]} mutant(s) at {place[0]}:{place[1]} not killed, but the triage names a test')
            if not (left - reasons):
                print(f'{batch}: final report leaves {sum(left.values())} not killed, all with a reason row')
        if expected != found:
            problems.append(f'{batch}: rows missing {dict(expected - found)}, extra {dict(found - expected)}')
        print(f'{batch}: {sum(expected.values())} not killed, {sum(found.values())} triage rows')
    print('decisions', dict(totals))
    for problem in problems:
        print('PROBLEM', problem)
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
