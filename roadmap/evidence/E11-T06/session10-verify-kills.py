#!/usr/bin/env python3
"""Prove that every `test:` decision of the session-10 survivor triage kills its mutant (E11-T06 step 8).

`run` executes a targeted PIT run in the private build copy `.local/e11-t06-ws` (same sources as the checkout plus the
staged `*Survivors3Test` classes; its own `target/`, so the gate's host-locked runs are untouched): the mutated classes
are those of the `test:` rows, the only tests are the new `Survivors3Test` classes, no history. `check` then requires,
for each `test:` row, that the targeted report holds the same mutation identity as KILLED, by a test of the named class.
"""
import argparse
from importlib.machinery import SourceFileLoader
import json
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
WS = ROOT / '.local/e11-t06-ws'
OUT = HERE / 'pit/session10'
TRIAGE = SourceFileLoader('triage', str(HERE / 'session6-triage.py')).load_module()


def test_rows(decisions):
    rows = {}
    for path in decisions:
        for identity, decision in json.loads(path.read_text()).items():
            if decision.startswith('test:'):
                rows[identity] = decision
    return rows


def run(name, decisions):
    rows = test_rows(decisions)
    classes = sorted({identity.split('|')[0].split('$')[0] for identity in rows})
    globs = [glob for top in classes for glob in (top, top + '$*')]
    command = ['./mvnw', '-q', '-f', str(WS.relative_to(ROOT) / 'pom.xml'), '-Pmutation', '-Djacoco.skip=true',
               '-DtargetClasses=' + ','.join(globs), '-DtargetTests=com.agilityhub.core.*Survivors3Test,com.agilityhub.core.*Survivors4Test',
               '-DreportsDirectory=target/pit-reports/' + name, 'test-compile', 'org.pitest:pitest-maven:mutationCoverage']
    print(f'{len(rows)} test decisions over {len(classes)} classes', flush=True)
    result = subprocess.run([sys.executable, str(HERE / 'run-evidence.py'), name, '--', *command], cwd=ROOT)
    if result.returncode == 0:
        shutil.copyfile(WS / 'target/pit-reports' / name / 'mutations.xml', OUT / (name + '-mutations.xml'))
    return result.returncode


def check(xml, decisions):
    rows = test_rows(decisions)
    report = {TRIAGE.identity(m): m for m in ET.parse(xml).getroot()}
    failures = 0
    for identity, decision in sorted(rows.items()):
        mutation = report.get(identity)
        test_class = decision.split(':', 1)[1].strip().split('#')[0].split('/')[-1].strip()
        killer = mutation.findtext('killingTest', '') if mutation is not None else ''
        ok = (mutation is not None and mutation.get('status') == 'KILLED'
              and int(mutation.get('numberOfTestsRun', '0')) > 0 and test_class in killer)
        if not ok:
            failures += 1
            status = 'ABSENT' if mutation is None else mutation.get('status')
            print(f'NOT KILLED BY ITS TEST ({status}, killer={killer or "-"}): {identity} -> {decision}')
    print(f'{len(rows) - failures} of {len(rows)} test decisions are killed by their named test class')
    return 1 if failures else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    r = sub.add_parser('run')
    r.add_argument('name')
    r.add_argument('decisions', type=Path, nargs='+')
    c = sub.add_parser('check')
    c.add_argument('xml', type=Path)
    c.add_argument('decisions', type=Path, nargs='+')
    args = parser.parse_args()
    return run(args.name, args.decisions) if args.command == 'run' else check(args.xml, args.decisions)


if __name__ == '__main__':
    sys.exit(main())
