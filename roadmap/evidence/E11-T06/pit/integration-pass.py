#!/usr/bin/env python3
"""Run one current package's integration supplement through the host lock.

Only this session's completed, clean unit run supplies history. The final merge keeps
its witnessed kills and credits a new kill only when the supplement actually ran tests.
"""
import argparse
from importlib.machinery import SourceFileLoader
import os
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
HEAVY = '/Users/jordib/Dropbox/Documents/SOFTWARE_CANIC/05-desenvolupament/roadmap-kit/mac/heavy.sh'
PAYMENT_TESTS = SourceFileLoader('commands', str(HERE / 'commands.py')).load_module().PAYMENT_TESTS
SELECTIONS = {
    'payments': ('com.agilityhub.core.payments.*', PAYMENT_TESTS),
    'bookings': ('com.agilityhub.core.clubs.bookings.*', 'com.agilityhub.core.clubs.bookings.*,'
                 'com.agilityhub.core.clubs.census.application.Demo*IT,com.agilityhub.core.clubs.scheduling.*IT'),
    'identity': ('com.agilityhub.core.identity.*', 'com.agilityhub.core.identity.*,'
                 'com.agilityhub.core.clubs.census.api.Signup*IT,com.agilityhub.core.migration.*IT'),
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('package', choices=SELECTIONS)
    parser.add_argument('evidence_name')
    args = parser.parse_args()
    classes, tests = SELECTIONS[args.package]
    assert (ROOT / '.local/pitest/session3-unit.bin').is_file(), 'The completed unit run is required'
    command = ['env', 'HEAVY_WAIT_MIN=25', HEAVY, './mvnw', '-q', '-Pmutation',
               '-DtargetClasses=' + classes, '-DtargetTests=' + tests,
               '-DmutationUnitSize=50', '-DreportsDirectory=target/pit-reports-session3-' + args.package,
               '-DhistoryInputFile=.local/pitest/session3-unit.bin',
               '-DhistoryOutputFile=.local/pitest/session3-' + args.package + '.bin',
               'test-compile', 'org.pitest:pitest-maven:mutationCoverage']
    os.execv(sys.executable, [sys.executable, str(HERE.parent / 'run-evidence.py'), args.evidence_name, '--', *command])


if __name__ == '__main__':
    main()
