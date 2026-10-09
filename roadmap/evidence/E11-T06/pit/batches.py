#!/usr/bin/env python3
"""E11-T06: the bounded PIT batches over the whole `payments`, `clubs.bookings` and `identity` packages.

Each batch fits one host-lock window (a command is bounded to one hour). Prints `<batch> <targetClasses>`; with a batch
name, prints only that batch's targetClasses. Every top-level class of the three packages belongs to exactly one batch
(checked against the sources), so the merged batch reports are the full packages.
"""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[4]
SOURCES = ROOT / 'src/main/java'
PAYMENTS = 'com.agilityhub.core.payments.'
BOOKINGS = 'com.agilityhub.core.clubs.bookings.'
IDENTITY = 'com.agilityhub.core.identity.'
# Batches 1-7 (payments) were cut by hand before the first runs; batches 8-14 split each application package
# alphabetically into chunks of similar size (top-level classes and their nested classes stay together).
PAYMENT_APPLICATION = {
    'batch2': 'AccountingLists AesGcmCipher BankAccountVault BillingContractAccess BillingEvents BillingExpirations '
              'BillingMigrationAccess BillingQueries BillingReminderJob BillingRunService BillingSimulationService '
              'BillingTexts BillingTransactions',
    'batch3': 'CardPayments CheckoutService FakeCheckoutGateway FakePaymentProvider FakeProviderCommand FakeWebhookCommand '
              'InvoiceActions InvoiceLists InvoiceNumbers InvoicingService ManualUpfrontPayments MemberPlanChangeService',
    'batch4': 'PackAuditLoader PackBalanceService PackViews PaymentAudits PaymentBookingCancellations PaymentCheckouts '
              'PaymentNotSubmitted PaymentNotificationFacts PaymentPrivacy PaymentProvider PaymentProviderRegistry '
              'PaymentProviderTimeouts PaymentRecovery PaymentRefunds PaymentRetryPolicy PaymentWebhookParser',
    'batch5': 'PendingChargeService ProviderSecretVault ReceiptPdf RemittanceLists RemittanceService SignupPaymentAccess '
              'StripeWebhookSignatures StripeWebhooks UpfrontPayments',
}
WHOLE_PACKAGES = {
    'batch1': [PAYMENTS + 'domain', PAYMENTS + 'application.sepa', PAYMENTS + 'application.stripe'],
    'batch5': [PAYMENTS + 'application.ports'], 'batch6': [PAYMENTS + 'api'], 'batch7': [PAYMENTS + 'persistence'],
    'batch8': [BOOKINGS + 'api', BOOKINGS + 'domain', BOOKINGS + 'persistence', BOOKINGS + 'application.jobs',
               BOOKINGS + 'application.ports'],
    'batch12': [IDENTITY + 'api', IDENTITY + 'domain', IDENTITY + 'persistence'],
}
CHUNKED = {('batch9', 'batch10', 'batch11'): BOOKINGS + 'application', ('batch13', 'batch14'): IDENTITY + 'application'}


def top_level_classes(package):
    directory = SOURCES / package.replace('.', '/')
    return sorted((source.stem, len(source.read_text().splitlines())) for source in directory.glob('*.java')
                  if source.name != 'package-info.java')


def chunks(classes, count):
    total, result, size = sum(lines for _, lines in classes), [[] for _ in range(count)], 0
    for name, lines in classes:
        result[min(count - 1, size * count // total)].append(name)
        size += lines
    return result


def batches():
    result = {}
    for name, classes in PAYMENT_APPLICATION.items():
        result.setdefault(name, []).extend(glob for simple in classes.split()
                                           for glob in (PAYMENTS + 'application.' + simple, PAYMENTS + 'application.' + simple + '$*'))
    for names, package in CHUNKED.items():
        for name, classes in zip(names, chunks(top_level_classes(package), len(names))):
            result.setdefault(name, []).extend(glob for simple in classes
                                               for glob in (package + '.' + simple, package + '.' + simple + '$*'))
    for name, packages in WHOLE_PACKAGES.items():
        result.setdefault(name, []).extend(package + '.*' for package in packages)
    return dict(sorted(result.items(), key=lambda item: int(item[0][5:])))


def owners(top_level, selected):
    def matches(glob):
        if glob.endswith('.*'):
            return top_level.startswith(glob[:-1]) and '.' not in top_level[len(glob) - 1:]
        return glob == top_level
    return [batch for batch, globs in selected.items() if any(matches(glob) for glob in globs)]


def main():
    selected = batches()
    for root in (PAYMENTS, BOOKINGS, IDENTITY):
        base = SOURCES / root.rstrip('.').replace('.', '/')
        for source in base.rglob('*.java'):
            if source.name == 'package-info.java':
                continue
            name = root + '.'.join(source.relative_to(base).with_suffix('').parts)
            found = owners(name, selected)
            assert len(found) == 1, f'{name} is in {found}'
    if len(sys.argv) > 1:
        print(','.join(selected[sys.argv[1]]))
    else:
        for batch, globs in selected.items():
            print(batch, len(globs), ','.join(globs)[:150])


if __name__ == '__main__':
    main()
