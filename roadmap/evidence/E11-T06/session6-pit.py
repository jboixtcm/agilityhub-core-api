#!/usr/bin/env python3
"""Fresh full-package PIT, one package per bounded host-lock invocation.

All unit tests plus the package's integration callers are selected. No history
input or output is used; every credited kill must execute current tests.
"""
import argparse
from importlib.machinery import SourceFileLoader
import os
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
HEAVY = '/Users/jordib/Dropbox/Documents/SOFTWARE_CANIC/05-desenvolupament/roadmap-kit/mac/heavy.sh'
PAYMENT_TESTS = SourceFileLoader('commands', str(HERE / 'pit/commands.py')).load_module().PAYMENT_TESTS
SELECTIONS = {
    'payments': ('com.agilityhub.core.payments.*', PAYMENT_TESTS + ',com.agilityhub.core.platform.api.*IT'),
    'bookings': ('com.agilityhub.core.clubs.bookings.*', 'com.agilityhub.core.clubs.bookings.*,'
                 'com.agilityhub.core.clubs.census.application.Demo*IT,com.agilityhub.core.clubs.scheduling.*IT,'
                 'com.agilityhub.core.clubs.training.*IT,com.agilityhub.core.clubs.activities.*IT'),
    'identity': ('com.agilityhub.core.identity.*', 'com.agilityhub.core.identity.*,'
                 'com.agilityhub.core.clubs.census.api.Signup*IT,com.agilityhub.core.migration.*IT,'
                 'com.agilityhub.core.platform.api.*IT'),
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('package', choices=SELECTIONS)
    parser.add_argument('evidence_name')
    args = parser.parse_args()
    classes, integration = SELECTIONS[args.package]
    command = [HEAVY, './mvnw', '-q', '-Pmutation', '-DtargetClasses=' + classes,
               '-DtargetTests=com.agilityhub.core.*Test,' + integration,
               '-DmutationUnitSize=50', '-DreportsDirectory=target/pit-reports/session6-' + args.package,
               'test-compile', 'org.pitest:pitest-maven:mutationCoverage']
    os.execv(sys.executable, [sys.executable, str(HERE / 'run-evidence.py'), args.evidence_name, '--', *command])


if __name__ == '__main__':
    main()
