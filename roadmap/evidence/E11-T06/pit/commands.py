#!/usr/bin/env python3
"""E11-T06: writes `NN-….command` next to each PIT log of this session: the exact command line that produced it.

The commands were typed as single shell lines; this script rebuilds them from the same pieces (batch selections from
`batches.py`, the two test selections) so that every log has its command. Existing `.command` files are not overwritten.
"""
from importlib.machinery import SourceFileLoader
from pathlib import Path

HERE = Path(__file__).resolve().parent
EVIDENCE = HERE.parent
HEAVY = '/Users/jordib/Dropbox/Documents/SOFTWARE_CANIC/05-desenvolupament/roadmap-kit/mac/heavy.sh'
PAYMENT_TESTS = ('com.agilityhub.core.payments.*,com.agilityhub.core.clubs.bookings.api.BookingsIT,'
                 'com.agilityhub.core.clubs.bookings.api.LifecycleConcurrencyIT,com.agilityhub.core.clubs.bookings.api.LifecycleIT,'
                 'com.agilityhub.core.clubs.bookings.api.SingleClassCheckoutIT,com.agilityhub.core.clubs.census.api.SignupCensusCorrectionsIT,'
                 'com.agilityhub.core.clubs.census.api.SignupFollowUpFixesIT,com.agilityhub.core.clubs.census.api.SignupGateFixesIT,'
                 'com.agilityhub.core.clubs.census.api.SignupIT,com.agilityhub.core.clubs.census.api.SignupMinorFixesIT,'
                 'com.agilityhub.core.clubs.dashboard.api.DashboardIT,com.agilityhub.core.clubs.messaging.application.engine.TemplateVariableParityIT,'
                 'com.agilityhub.core.configuration.E3ContractIT,com.agilityhub.core.configuration.E3ResponseContractTest,'
                 'com.agilityhub.core.configuration.E5BackOfficeContractTest,com.agilityhub.core.configuration.E8ContractIT,'
                 'com.agilityhub.core.configuration.E8PersistenceIT,com.agilityhub.core.configuration.E8ResponseContractTest,'
                 'com.agilityhub.core.configuration.ListFieldsContractIT,com.agilityhub.core.configuration.S04ErrorContractTest,'
                 'com.agilityhub.core.migration.PlayoffAdapterTest,com.agilityhub.core.migration.PlayoffMigrationIT,'
                 'com.agilityhub.core.shared.api.AnonymousRateLimitsTest')
ALL_TESTS = ('com.agilityhub.core.cli.*,com.agilityhub.core.clubs.*,com.agilityhub.core.configuration.*,com.agilityhub.core.courses.*,'
             'com.agilityhub.core.identity.*,com.agilityhub.core.migration.*,com.agilityhub.core.payments.*,com.agilityhub.core.platform.*,'
             'com.agilityhub.core.shared.api.*,com.agilityhub.core.shared.application.*,com.agilityhub.core.shared.persistence.*,'
             'com.agilityhub.core.shared.domain.ErrorCatalogContractTest,com.agilityhub.core.shared.domain.LocalizedTextTest,'
             'com.agilityhub.core.shared.domain.MoneyTest,com.agilityhub.core.support.*')
BT = 'com.agilityhub.core.payments.application.BillingTransactions,com.agilityhub.core.payments.application.BillingTransactions$*'


def command(classes, tests, log, unit=10, extra='', prefix=''):
    tests_part = f" '-DtargetTests={tests}'" if tests else ''
    return (f"{prefix}{HEAVY} ./mvnw -q -Pmutation{extra} '-DtargetClasses={classes}'{tests_part} -DmutationUnitSize={unit} "
            f"test-compile org.pitest:pitest-maven:mutationCoverage > roadmap/evidence/E11-T06/{log} 2>&1")


def main():
    select = SourceFileLoader('batches', str(HERE / 'batches.py')).load_module().batches()
    sel = {name: ','.join(globs) for name, globs in select.items()}
    seed = lambda name: f'cp .local/pitest/{name} .local/pitest/history.bin && '
    runs = {
        '28-pit-payments-batch1.log': command(sel['batch1'], PAYMENT_TESTS, '28-pit-payments-batch1.log'),
        '30-pit-payments-batch2.log': command(sel['batch2'], None, '30-pit-payments-batch2.log', extra=' -Dthreads=8'),
        '31-pit-payments-batch2.log': command(sel['batch2'], PAYMENT_TESTS, '31-pit-payments-batch2.log', extra=' -Dthreads=8'),
        '32-pit-payments-batch3.log': command(sel['batch3'], PAYMENT_TESTS, '32-pit-payments-batch3.log'),
        '34-pit-payments-batch4.log': command(sel['batch4'], PAYMENT_TESTS, '34-pit-payments-batch4.log'),
        '36-pit-payments-batch5.log': command(sel['batch5'], PAYMENT_TESTS, '36-pit-payments-batch5.log'),
        '38-pit-payments-batch6.log': command(sel['batch6'], PAYMENT_TESTS, '38-pit-payments-batch6.log'),
        '40-pit-payments-batch7.log': command(sel['batch7'], PAYMENT_TESTS, '40-pit-payments-batch7.log'),
        '42-pit-bookings-batch8.log': command(sel['batch8'], ALL_TESTS, '42-pit-bookings-batch8.log',
                                              prefix=seed('attempt141-complete-history.bin')),
        '45-pit-payments-rerun1.log': command(sel['batch1'], PAYMENT_TESTS, '45-pit-payments-rerun1.log',
                                              prefix=seed('e11-t06-batch1-history.bin')),
        '46-pit-payments-rerun2.log': command(sel['batch2'], PAYMENT_TESTS, '46-pit-payments-rerun2.log'),
        '47-pit-payments-rerun3.log': command(sel['batch3'], PAYMENT_TESTS, '47-pit-payments-rerun3.log',
                                              prefix=seed('e11-t06-batch3-history.bin')),
        '49-pit-payments-rerun4.log': command(sel['batch4'], PAYMENT_TESTS, '49-pit-payments-rerun4.log',
                                              prefix=seed('e11-t06-batch4-history.bin')),
        '50-pit-payments-rerun5.log': command(sel['batch5'], PAYMENT_TESTS, '50-pit-payments-rerun5.log',
                                              prefix=seed('e11-t06-batch5-history.bin')),
        '51-pit-payments-rerun6.log': command(sel['batch6'], PAYMENT_TESTS, '51-pit-payments-rerun6.log',
                                              prefix=seed('e11-t06-batch6-history.bin')),
        '52-pit-payments-rerun7.log': command(sel['batch7'], PAYMENT_TESTS, '52-pit-payments-rerun7.log',
                                              prefix=seed('e11-t06-batch7-history.bin')),
        # The history copy for 53 ran as the last step of the previous command line (after saving re-run 7).
        '53-pit-payments-rerun2b.log': command(sel['batch2'], PAYMENT_TESTS, '53-pit-payments-rerun2b.log',
                                               prefix=seed('e11-t06-final2-history.bin')),
        '55-pit-billing-transactions.log': command(BT, PAYMENT_TESTS, '55-pit-billing-transactions.log',
                                                   prefix=seed('e11-t06-final1-history.bin')),
        '65-pit-bookings-batch9.log': command(sel['batch9'], ALL_TESTS, '65-pit-bookings-batch9.log', unit=50,
                                              extra='', prefix=seed('attempt141-complete-history.bin')).replace(
            ' -DmutationUnitSize=50 ', ' -DmutationUnitSize=50 -DreportsDirectory=target/pit-reports-batch9 '
            '-DhistoryInputFile=.local/pitest/attempt141-complete-history.bin '
            '-DhistoryOutputFile=.local/pitest/e11-t06-batch9-history.bin '),
        '72-pit-bookings-batch10.log': command(sel['batch10'], ALL_TESTS, '72-pit-bookings-batch10.log', unit=50).replace(
            ' -DmutationUnitSize=50 ', ' -DmutationUnitSize=50 -DreportsDirectory=target/pit-reports-batch10 '),
        '74-pit-bookings-batch10.log': command(sel['batch10'], ALL_TESTS, '74-pit-bookings-batch10.log', unit=50).replace(
            ' -DmutationUnitSize=50 ', ' -DmutationUnitSize=50 -DreportsDirectory=target/pit-reports-batch10 '),
        '78-pit-bookings-batch11.log': command(sel['batch11'], ALL_TESTS, '78-pit-bookings-batch11.log', unit=50).replace(
            ' -DmutationUnitSize=50 ', ' -DmutationUnitSize=50 -DreportsDirectory=target/pit-reports-batch11 '),
        '81-pit-identity-batch12.log': command(sel['batch12'], ALL_TESTS, '81-pit-identity-batch12.log', unit=50).replace(
            ' -DmutationUnitSize=50 ', ' -DmutationUnitSize=50 -DreportsDirectory=target/pit-reports-batch12 '),
        '83-pit-identity-batch13-14.log': command('com.agilityhub.core.identity.application.*', ALL_TESTS,
                                                  '83-pit-identity-batch13-14.log', unit=50).replace(
            ' -DmutationUnitSize=50 ', ' -DmutationUnitSize=50 -DreportsDirectory=target/pit-reports-batch13-14 '),
    }
    for log, line in runs.items():
        target = EVIDENCE / log.replace('.log', '.command')
        if target.exists():
            print('kept', target.name)
            continue
        target.write_text(line + '\n')
        print('wrote', target.name)


if __name__ == '__main__':
    main()
