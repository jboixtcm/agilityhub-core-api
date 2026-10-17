#!/usr/bin/env python3
"""Session 11: bring the session-10 gate to the current `main` (E11-T06 step 8).

Between session 10's baseline `e94adcb0` and the session-11 baseline, `main` changed 17 classes of the three mutated
packages (E10-T03/E10-T04: support access, impersonation; four controllers gained one `@SupportRestricted` line, which
shifts every later line number of their mutants). Every other class of the three packages is byte-identical.

`run` mutates exactly the changed classes of one package, fresh: no history input, all unit tests plus the package's
session-6 integration selection (the selection of every session-10 supplement), through the host lock and the
evidence runner's one-hour deadline.

`splice` writes the gate report of the current code: session 10's merged report (`218`) without the rows of the changed
classes, with the kills that the targeted runs of the staged survivor tests proved (`196`-`223`, identity-matched as in
`pit/union.py`, restricted to unchanged classes), plus the fresh rows of the changed classes. It refuses when a class
outside the delta changed, when a delta class is missing from the fresh runs, or when a mutant appears twice.
"""
import argparse
from importlib.machinery import SourceFileLoader
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
OUT = HERE / 'pit/session11'
S8 = SourceFileLoader('session8', str(HERE / 'session8-pit.py')).load_module()
BASELINE = 'e94adcb0'
SOURCE = 'src/main/java/'
DELTA = {
    'identity': ['identity.api.AccountAdministrationController', 'identity.api.CurrentUserFilter',
                 'identity.api.MeController', 'identity.api.MeResponse', 'identity.api.SupportAccessesController',
                 'identity.application.DemoPlatformAccountsSeeder', 'identity.application.DomainBrokenNotices',
                 'identity.application.ImpersonationService', 'identity.application.SupportAccessService',
                 'identity.domain.SupportAccessEnded', 'identity.domain.SupportAccessStarted',
                 'identity.persistence.AccountRepository', 'identity.persistence.ImpersonationGrant'],
    'payments': ['payments.api.BillingController', 'payments.api.RemittanceFilesController',
                 'payments.api.RemittancesController'],
    'bookings': ['clubs.bookings.api.InstructorController'],
}
TARGETED = ['196-session10-targeted-payments', '197-session10-targeted-checkout-contracts',
            '198-session10-targeted-bookings-b0-b3', '206-session10-targeted-bookings-b4-b5',
            '223-session10-targeted-identity-final']
FIELDS = ('mutatedClass', 'mutatedMethod', 'methodDescription', 'lineNumber', 'mutator', 'description')


def full(short):
    return 'com.agilityhub.core.' + short


def identity(mutation):
    return tuple(mutation.findtext(name, '') for name in FIELDS) + tuple(
        tuple(node.text for node in mutation.iter(tag)) for tag in ('index', 'block'))


def top(mutation):
    return mutation.findtext('mutatedClass').split('$')[0]


def changed_classes():
    """Top-level classes of the three packages whose source differs from session 10's baseline."""
    paths = [SOURCE + prefix.rstrip('.').replace('.', '/') for prefix in S8.PACKAGES.values()]
    names = subprocess.run(['git', 'diff', '--name-only', BASELINE, 'HEAD', '--', *paths], cwd=ROOT, check=True,
                           capture_output=True, text=True).stdout.split()
    dirty = subprocess.run(['git', 'status', '--porcelain', '--', *paths], cwd=ROOT, check=True,
                           capture_output=True, text=True).stdout.strip()
    assert not dirty, 'Uncommitted changes in a mutated package'
    return {name[len(SOURCE):-len('.java')].replace('/', '.') for name in names}


def run(package, name):
    classes = [full(short) for short in DELTA[package]]
    globs = [glob for clazz in classes for glob in (clazz, clazz + '$*')]
    reports = 'target/pit-reports/session11-' + name
    command = [S8.HEAVY, './mvnw', '-q', '-Pmutation', '-DtargetClasses=' + ','.join(globs),
               '-DtargetTests=com.agilityhub.core.*Test,' + S8.SELECTIONS[package][1], '-DmutationUnitSize=50',
               '-DreportsDirectory=' + reports, 'test-compile', 'org.pitest:pitest-maven:mutationCoverage']
    result = subprocess.run([sys.executable, str(HERE / 'run-evidence.py'), name, '--', *command], cwd=ROOT)
    if result.returncode == 0:
        OUT.mkdir(exist_ok=True)
        target = OUT / (name + '-mutations.xml')
        shutil.copyfile(ROOT / reports / 'mutations.xml', target)
        found = {top(m) for m in ET.parse(target).getroot()}
        assert found <= set(classes), 'Report holds a class outside the delta'
        print(f'{target.name}: {len(found)} of {len(classes)} delta classes have mutants: '
              + ', '.join(sorted(c.rsplit('.', 1)[-1] for c in found)))
    return result.returncode


def splice(fresh, output):
    delta = {full(short) for shorts in DELTA.values() for short in shorts}
    changed = changed_classes()
    assert changed == delta, f'Delta differs from git: missing {sorted(changed - delta)}, extra {sorted(delta - changed)}'
    root = ET.parse(HERE / 'pit/session10/merged-mutations.xml').getroot()
    kept = [m for m in root if top(m) not in delta]
    dropped = len(root) - len(kept)
    position = {identity(m): i for i, m in enumerate(kept)}
    assert len(position) == len(kept), 'Duplicate mutant in the session-10 report'
    promoted = 0
    for name in TARGETED:
        for mutation in ET.parse(HERE / 'pit/session10' / (name + '-mutations.xml')).getroot():
            if top(mutation) in delta or mutation.get('status') != 'KILLED':
                continue
            key = identity(mutation)
            assert key in position, f'{name}: a targeted mutant of an unchanged class is not in the report'
            assert int(mutation.get('numberOfTestsRun', '0')) > 0, 'Unwitnessed targeted kill'
            if kept[position[key]].get('status') != 'KILLED':
                kept[position[key]] = mutation
                promoted += 1
    added, reported = [], set()
    for path in fresh:
        for mutation in ET.parse(path).getroot():
            assert top(mutation) in delta, f'{path.name}: {top(mutation)} is not a delta class'
            key = identity(mutation)
            assert key not in position, f'{path.name}: mutant reported twice'
            position[key] = -1
            reported.add(top(mutation))
            added.append(mutation)
    result = ET.Element('mutations')
    result.extend(kept + added)
    ET.ElementTree(result).write(output, encoding='UTF-8', xml_declaration=True)
    print(f'Delta = git diff {BASELINE} HEAD: {len(delta)} classes; {dropped} session-10 rows of them dropped')
    print(f'{promoted} not-killed rows of unchanged classes promoted by the targeted survivor-test runs')
    print(f'{len(added)} fresh rows from {len(fresh)} runs; {len(reported)} delta classes have mutants; '
          f'without mutants: ' + ', '.join(sorted(c.rsplit('.', 1)[-1] for c in delta - reported)))
    print(f'{output.relative_to(ROOT)}: {len(result)} mutations')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    r = sub.add_parser('run')
    r.add_argument('package', choices=DELTA)
    r.add_argument('name')
    s = sub.add_parser('splice')
    s.add_argument('output', type=Path)
    s.add_argument('fresh', type=Path, nargs='+')
    args = parser.parse_args()
    if args.command == 'run':
        return run(args.package, args.name)
    splice(args.fresh, args.output.resolve())
    return 0


if __name__ == '__main__':
    sys.exit(main())
