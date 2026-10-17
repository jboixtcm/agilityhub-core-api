#!/usr/bin/env python3
"""Session 10: run one part of a fixed session-8 batch (E11-T06 step 8).

The host lock's wait counts against the evidence runner's one-hour deadline, and the other lane's clean verify
holds it for about 20 minutes (`166` timed out after such a wait). `run-part` therefore cuts a batch of
`pit/session8/plan.json` into contiguous parts balanced by the mutants the unit pass did not kill, and runs one
part with exactly session 8's command (`session8-pit.py run`): same integration selection, same unit history.
`check-parts` proves that the reports of the parts cover only, and without overlap, the classes of their batch.
"""
import argparse
from collections import Counter
from importlib.machinery import SourceFileLoader
import json
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
S8 = SourceFileLoader('session8', str(HERE / 'session8-pit.py')).load_module()


def parts_of(package, index, parts):
    batch = json.loads(S8.PLAN.read_text())['packages'][package][index]
    weights = Counter()
    for mutation in ET.parse(S8.UNIT_XML).getroot():
        weights[mutation.findtext('mutatedClass').split('$')[0]] += mutation.get('status') != 'KILLED'
    classes = batch['classes']
    assert sum(weights[top] for top in classes) == batch['notKilledByUnit'], 'Plan and unit pass disagree'
    target = batch['notKilledByUnit'] / parts
    result, current, size = [], [], 0
    for top in classes:
        if current and size + weights[top] / 2 > target and len(result) < parts - 1:
            result.append(current)
            current, size = [], 0
        current.append(top)
        size += weights[top]
    result.append(current)
    assert [top for part in result for top in part] == classes
    return result, [sum(weights[top] for top in part) for part in result]


def run_part(package, index, part, parts, name):
    groups, weights = parts_of(package, index, parts)
    print(f'{package} batch {index} part {part + 1}/{len(groups)}: {len(groups[part])} classes, '
          f'{weights[part]} not killed by the unit pass', flush=True)
    return run_classes(package, groups[part], name)


def run_explicit(package, index, short_names, name):
    """A part named class by class (the demo seeders' mutants each run ~20-second demo ITs: `183`)."""
    batch = json.loads(S8.PLAN.read_text())['packages'][package][index]['classes']
    by_short = {top.rsplit('.', 1)[-1]: top for top in batch}
    classes = [by_short[short] for short in short_names]
    print(f'{package} batch {index} explicit part: ' + ', '.join(short_names), flush=True)
    return run_classes(package, classes, name)


def run_classes(package, classes, name):
    assert S8.UNIT_HISTORY.is_file(), 'The session-8 unit pass is required'
    globs = [glob for top in classes for glob in (top, top + '$*')]
    reports = 'target/pit-reports/session10-' + name
    command = [S8.HEAVY, './mvnw', '-q', '-Pmutation', '-DtargetClasses=' + ','.join(globs),
               '-DtargetTests=com.agilityhub.core.*Test,' + S8.SELECTIONS[package][1], '-DmutationUnitSize=50',
               '-DreportsDirectory=' + reports, '-DhistoryInputFile=' + str(S8.UNIT_HISTORY.relative_to(S8.ROOT)),
               '-DhistoryOutputFile=.local/pitest/session10-' + name + '.bin',
               'test-compile', 'org.pitest:pitest-maven:mutationCoverage']
    result = subprocess.run([sys.executable, str(HERE / 'run-evidence.py'), name, '--', *command], cwd=S8.ROOT)
    if result.returncode == 0:
        target = S8.OUT / (name + '-mutations.xml')
        shutil.copyfile(S8.ROOT / reports / 'mutations.xml', target)
        found = {m.findtext('mutatedClass').split('$')[0] for m in ET.parse(target).getroot()}
        assert found <= set(classes), 'Report holds a class outside its part'
        print(f'{target.name}: {len(found)} of {len(classes)} part classes reported')
    return result.returncode


def check_parts(package, index, xmls):
    batch = set(json.loads(S8.PLAN.read_text())['packages'][package][index]['classes'])
    seen = {}
    for path in xmls:
        for mutation in ET.parse(path).getroot():
            top = mutation.findtext('mutatedClass').split('$')[0]
            assert top in batch, f'{path.name}: {top} is outside batch {index}'
            assert seen.setdefault(top, path) == path, f'{top} reported by {seen[top].name} and {path.name}'
    print(f'PASS {package} batch {index}: {len(seen)} of {len(batch)} classes reported, each by one part')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    s = sub.add_parser('show')
    s.add_argument('package', choices=S8.PACKAGES)
    s.add_argument('index', type=int)
    s.add_argument('parts', type=int)
    r = sub.add_parser('run-part')
    r.add_argument('package', choices=S8.PACKAGES)
    r.add_argument('index', type=int)
    r.add_argument('part', type=int, help='0-based')
    r.add_argument('parts', type=int)
    r.add_argument('name')
    e = sub.add_parser('run-classes')
    e.add_argument('package', choices=S8.PACKAGES)
    e.add_argument('index', type=int)
    e.add_argument('name')
    e.add_argument('classes', nargs='+', help='simple names of top-level classes of the batch')
    c = sub.add_parser('check-parts')
    c.add_argument('package', choices=S8.PACKAGES)
    c.add_argument('index', type=int)
    c.add_argument('xml', type=Path, nargs='+')
    args = parser.parse_args()
    if args.command == 'show':
        groups, weights = parts_of(args.package, args.index, args.parts)
        for group, weight in zip(groups, weights):
            print(f'{weight} not killed: ' + ', '.join(top.rsplit('.', 1)[-1] for top in group))
        return 0
    if args.command == 'run-part':
        return run_part(args.package, args.index, args.part, args.parts, args.name)
    if args.command == 'run-classes':
        return run_explicit(args.package, args.index, args.classes, args.name)
    check_parts(args.package, args.index, args.xml)
    return 0


if __name__ == '__main__':
    sys.exit(main())
