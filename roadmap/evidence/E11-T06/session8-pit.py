#!/usr/bin/env python3
"""Session 8 mutation verification on the current source (E11-T06 step 8).

1. `152` is a fresh full-scope unit pass (no history input) that writes `.local/pitest/session8-unit.bin`.
2. `plan` cuts each package into class batches by the number of mutants the unit pass did not kill,
   so every integration supplement fits one host-lock window under the one-hour deadline.
3. `run` executes one batch with the session-6 integration selection; its only history input is this
   session's unit pass, so PIT re-runs exactly the mutants that pass did not kill.
4. `concat` joins a package's batches into one supplement (a mutant reported twice is refused), and
   `pit/merge-current.py` merges the supplements into the unit pass without crediting unwitnessed kills.
"""
import argparse
from collections import Counter, defaultdict
from importlib.machinery import SourceFileLoader
import json
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
OUT = HERE / 'pit/session8'
UNIT_XML = OUT / 'unit-mutations.xml'
UNIT_HISTORY = ROOT / '.local/pitest/session8-unit.bin'
PLAN = OUT / 'plan.json'
HEAVY = '/Users/jordib/Dropbox/Documents/SOFTWARE_CANIC/05-desenvolupament/roadmap-kit/mac/heavy.sh'
SELECTIONS = SourceFileLoader('session6', str(HERE / 'session6-pit.py')).load_module().SELECTIONS
PACKAGES = {'bookings': 'com.agilityhub.core.clubs.bookings.',
            'identity': 'com.agilityhub.core.identity.', 'payments': 'com.agilityhub.core.payments.'}


def package_of(clazz):
    return next(name for name, prefix in PACKAGES.items() if clazz.startswith(prefix))


def summary(paths):
    for path in paths:
        counts = defaultdict(Counter)
        for mutation in ET.parse(path).getroot():
            counts[package_of(mutation.findtext('mutatedClass'))][mutation.get('status')] += 1
        print(path.name)
        for name in sorted(counts):
            total = sum(counts[name].values())
            print(f'  {name}: generated={total} killed={counts[name]["KILLED"]} '
                  f'({100 * counts[name]["KILLED"] / total:.2f}%) ' + json.dumps(dict(sorted(counts[name].items()))))


def plan(limit):
    if PLAN.exists():
        raise SystemExit('Plan exists; it is fixed for the whole session')
    weights = {name: Counter() for name in PACKAGES}
    for mutation in ET.parse(UNIT_XML).getroot():
        clazz = mutation.findtext('mutatedClass')
        top = clazz.split('$')[0]
        weights[package_of(clazz)][top] += mutation.get('status') != 'KILLED'
    result = {}
    for name, classes in weights.items():
        batches, current, size = [], [], 0
        for top, weight in sorted(classes.items()):
            if current and size + weight > limit:
                batches.append(current)
                current, size = [], 0
            current.append(top)
            size += weight
        batches.append(current)
        result[name] = [{'classes': batch, 'notKilledByUnit': sum(classes[top] for top in batch)} for batch in batches]
        print(f'{name}: {len(classes)} top-level classes, {sum(classes.values())} not killed by the unit pass, '
              f'{len(batches)} batches: ' + ', '.join(str(b['notKilledByUnit']) for b in result[name]))
    PLAN.write_text(json.dumps({'limit': limit, 'packages': result}, indent=2) + '\n')


def run(package, index, name):
    assert UNIT_HISTORY.is_file(), 'The session-8 unit pass is required'
    batch = json.loads(PLAN.read_text())['packages'][package][index]
    globs = [glob for top in batch['classes'] for glob in (top, top + '$*')]
    integration = SELECTIONS[package][1]
    reports = 'target/pit-reports/session8-' + name
    command = [HEAVY, './mvnw', '-q', '-Pmutation', '-DtargetClasses=' + ','.join(globs),
               '-DtargetTests=com.agilityhub.core.*Test,' + integration, '-DmutationUnitSize=50',
               '-DreportsDirectory=' + reports, '-DhistoryInputFile=' + str(UNIT_HISTORY.relative_to(ROOT)),
               '-DhistoryOutputFile=.local/pitest/session8-' + name + '.bin',
               'test-compile', 'org.pitest:pitest-maven:mutationCoverage']
    result = subprocess.run([sys.executable, str(HERE / 'run-evidence.py'), name, '--', *command], cwd=ROOT)
    if result.returncode == 0:
        target = OUT / (name + '-mutations.xml')
        shutil.copyfile(ROOT / reports / 'mutations.xml', target)
        expected = {top for top in batch['classes']}
        found = {m.findtext('mutatedClass').split('$')[0] for m in ET.parse(target).getroot()}
        assert found <= expected, 'Report holds a class outside its batch'
        print(f'{target.name}: {len(found)} of {len(expected)} batch classes reported')
    return result.returncode


def concat(output, paths):
    if output.exists():
        raise SystemExit('Output exists')
    root, seen = None, set()
    gate = SourceFileLoader('gate', str(ROOT / 'bin/mutation-gate')).load_module()
    gate.merge(paths)  # refuses a mutation reported by two batches
    for path in paths:
        tree = ET.parse(path).getroot()
        if root is None:
            root = tree
        else:
            root.extend(list(tree))
    ET.ElementTree(root).write(output, encoding='UTF-8', xml_declaration=True)
    print(f'{output.name}: {len(root)} mutations from {len(paths)} batches')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    s = sub.add_parser('summary')
    s.add_argument('xml', type=Path, nargs='+')
    p = sub.add_parser('plan')
    p.add_argument('--limit', type=int, required=True)
    r = sub.add_parser('run')
    r.add_argument('package', choices=PACKAGES)
    r.add_argument('index', type=int)
    r.add_argument('name')
    c = sub.add_parser('concat')
    c.add_argument('output', type=Path)
    c.add_argument('xml', type=Path, nargs='+')
    args = parser.parse_args()
    if args.command == 'summary':
        summary(args.xml)
    elif args.command == 'plan':
        plan(args.limit)
    elif args.command == 'run':
        return run(args.package, args.index, args.name)
    else:
        concat(args.output, args.xml)
    return 0


if __name__ == '__main__':
    sys.exit(main())
