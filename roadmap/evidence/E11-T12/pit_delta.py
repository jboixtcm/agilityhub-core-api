#!/usr/bin/env python3
"""E11-T12 step 7: E11-T06 session 11's delta method on the current `main`.

The gate report of E11-T06 is `roadmap/evidence/E11-T06/pit/session11/gate-mutations.xml` (baseline `32a4f066`). Since
then `main` changed classes of the three mutated packages. This script:

- `plan`: lists the changed source files (`git diff --name-status 32a4f066 HEAD`), how many gate rows each one has, the
  batches below and, per package, the integration tests outside the package's E11-T06 selection that name a delta class
  (added to that package's selection, so a class tested only from another context is not scored uncovered).
- `run BATCH NN`: mutates exactly that batch's delta classes (top-level class + `$*`), fresh — no history input — with
  every unit test plus the package's integration selection, through the host lock and a one-hour deadline; the log is
  `NN-pit-BATCH.log` (with `.command` and `.exit`), the report is copied to `pit/NN-BATCH-mutations.xml`.
- `splice OUT FRESH…`: the gate report without the rows of the delta source files, plus the fresh rows. It refuses when
  git's delta differs from the batches, when a source file outside the delta changed, when a fresh row belongs to a
  class outside the delta, when a mutant appears twice, or when a delta file still present at HEAD has no fresh run.

Rows are attributed to a source file by `mutatedClass`'s package + `sourceFile` (nested classes included). Run from the
repository root. Scoring is `bin/mutation-gate` on the spliced report.
"""
import argparse
from collections import Counter, defaultdict
import os
from pathlib import Path
import re
import shlex
import shutil
import signal
import subprocess
import sys
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
PIT = HERE / 'pit'
GATE = ROOT / 'roadmap/evidence/E11-T06/pit/session11/gate-mutations.xml'
BASELINE = '32a4f066'
HEAVY = '/Users/jordib/Dropbox/Documents/SOFTWARE_CANIC/05-desenvolupament/roadmap-kit/mac/heavy.sh'
SOURCE = 'src/main/java/'
PACKAGES = {'bookings': 'com/agilityhub/core/clubs/bookings/', 'identity': 'com/agilityhub/core/identity/',
            'payments': 'com/agilityhub/core/payments/'}
# E11-T06's integration selections (session 6, used by every session-10/11 run), per package.
SELECTIONS = {
    'payments': 'com.agilityhub.core.payments.*,com.agilityhub.core.clubs.bookings.api.BookingsIT,'
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
                'com.agilityhub.core.shared.api.AnonymousRateLimitsTest,com.agilityhub.core.platform.api.*IT',
    'bookings': 'com.agilityhub.core.clubs.bookings.*,com.agilityhub.core.clubs.census.application.Demo*IT,'
                'com.agilityhub.core.clubs.scheduling.*IT,com.agilityhub.core.clubs.training.*IT,com.agilityhub.core.clubs.activities.*IT',
    'identity': 'com.agilityhub.core.identity.*,com.agilityhub.core.clubs.census.api.Signup*IT,com.agilityhub.core.migration.*IT,'
                'com.agilityhub.core.platform.api.*IT',
}
# Bounded batches (each inside the one-hour deadline), by package; names are relative to com.agilityhub.core.
BATCHES = {
    'bookings-1': ('bookings', ['clubs.bookings.api.BookingContracts', 'clubs.bookings.api.BookingsController',
                                'clubs.bookings.application.AttendanceSheetService', 'clubs.bookings.application.BookableClassesQuery',
                                'clubs.bookings.application.BookingChecks', 'clubs.bookings.application.BookingConfirmationService',
                                'clubs.bookings.application.BookingQueryService', 'clubs.bookings.application.BookingTransactions',
                                'clubs.bookings.application.BookingViews', 'clubs.bookings.application.BookingsErasure',
                                'clubs.bookings.application.jobs.ClassFinishingJob', 'clubs.bookings.application.jobs.NoShowNoticesJob',
                                'clubs.bookings.application.ports.WaitlistConsolidationPort', 'clubs.bookings.domain.BookingCalendar']),
    'bookings-2': ('bookings', ['clubs.bookings.application.SeatHoldService', 'clubs.bookings.application.WaitlistService',
                                'clubs.bookings.application.WaitlistTransitions', 'clubs.bookings.persistence.AttendanceRepository',
                                'clubs.bookings.persistence.BookingErasureRepository', 'clubs.bookings.persistence.BookingRepository',
                                'clubs.bookings.persistence.SeatHoldRepository', 'clubs.bookings.persistence.SeatLock',
                                'clubs.bookings.persistence.SeatLockRepository', 'clubs.bookings.persistence.WaitlistEntryRepository']),
    'identity-1': ('identity', ['identity.api.AuthController', 'identity.api.CurrentUserFilter', 'identity.api.MeController',
                                'identity.api.OAuthController', 'identity.api.PasswordGrantAuthenticationToken',
                                'identity.api.PasswordGrantConverter', 'identity.api.PasswordGrantProvider',
                                'identity.domain.ImpersonationEvent', 'identity.persistence.AccountMembershipRepository',
                                'identity.persistence.AccountRepository', 'identity.persistence.AccountSessionRepository',
                                'identity.persistence.HandoffCodeRepository', 'identity.persistence.IdentityErasureRepository',
                                'identity.persistence.ImpersonationGrant', 'identity.persistence.ImpersonationGrantRepository',
                                'identity.persistence.MagicLinkTokenRepository', 'identity.persistence.MembershipRepository',
                                'identity.persistence.OidcStateRepository', 'identity.persistence.RefreshTokenRepository']),
    # identity-1 ran 263 mutations in 32 min: the larger groups are cut to about 300 gate rows each (gate counts per class).
    'identity-2a': ('identity', ['identity.application.AccountErasureHandler', 'identity.application.IdentityErasure',
                                 'identity.application.OidcService', 'identity.application.PasswordHasher',
                                 'identity.application.PasswordService', 'identity.application.SigningKeys',
                                 'identity.application.TokenService']),
    # identity-2a (389 mutations) took 53 min of the 60: the rest is cut to about 130–240 gate rows per batch (lock waits count).
    'identity-2b': ('identity', ['identity.application.CensusIdentityService', 'identity.application.DemoPlatformAccountsSeeder',
                                 'identity.application.HandoffService', 'identity.application.IdentityService',
                                 'identity.application.ImpersonationExpiry', 'identity.application.ImpersonationService']),
    'identity-2c': ('identity', ['identity.application.LearnImportService', 'identity.application.MagicLinkService',
                                 'identity.application.SupportAccessService']),
    'payments-1a': ('payments', ['payments.api.BillingContracts', 'payments.api.BillingController', 'payments.api.BillingRequests',
                                 'payments.api.BillingViews', 'payments.api.InvoicesController', 'payments.api.MemberBillingController',
                                 'payments.api.MemberPlanChangeController', 'payments.domain.SepaDirectDebits']),
    'payments-1b': ('payments', ['payments.persistence.BillingDocuments', 'payments.persistence.PaymentOperationRepository',
                                 'payments.persistence.PaymentsErasureRepository', 'payments.persistence.UpfrontPaymentRepository']),
    'payments-1c': ('payments', ['payments.domain.BillingIncidentCode', 'payments.domain.InvoicingRules',
                                 'payments.domain.PackBalanceState', 'payments.domain.RollbackBlocker']),
    'payments-1d': ('payments', ['payments.application.sepa.Pain008Document', 'payments.application.sepa.SepaRemittanceWriter']),
    'payments-2a': ('payments', ['payments.application.BillingRunService', 'payments.application.ConsolePaymentValidation']),
    'payments-2b': ('payments', ['payments.application.CheckoutService', 'payments.application.InvoiceActions']),
    'payments-2c': ('payments', ['payments.application.BankAccountVault', 'payments.application.BillingExpirations',
                                 'payments.application.BillingQueries', 'payments.application.InvoicingService']),
    'payments-3a': ('payments', ['payments.application.PaymentRefunds']),
    'payments-3b': ('payments', ['payments.application.MemberNextInvoiceDateService', 'payments.application.MemberPlanChangeService',
                                 'payments.application.PackBalanceService']),
    'payments-3c': ('payments', ['payments.application.PaymentCheckouts', 'payments.application.PaymentsErasure',
                                 'payments.application.UpfrontPayments']),
}


def git(*args):
    return subprocess.run(['git', *args], cwd=ROOT, check=True, capture_output=True, text=True).stdout


def delta():
    """{source path relative to src/main/java: status} for the three packages, BASELINE → HEAD."""
    paths = [SOURCE + prefix for prefix in PACKAGES.values()]
    dirty = git('status', '--porcelain', '--', *paths).strip()
    if dirty:
        sys.exit('Uncommitted changes in a mutated package:\n' + dirty)
    out = {}
    for line in git('diff', '--name-status', '--no-renames', BASELINE, 'HEAD', '--', *paths).splitlines():
        status, name = line.split('\t', 1)
        out[name[len(SOURCE):]] = status
    return out


def batch_files():
    return {short.replace('.', '/') + '.java': name for name, (_, shorts) in BATCHES.items() for short in shorts}


def source_of(mutation):
    """com/agilityhub/core/x/Y.java for a row (its class's package + its sourceFile)."""
    clazz = mutation.findtext('mutatedClass')
    return clazz.rsplit('.', 1)[0].replace('.', '/') + '/' + mutation.findtext('sourceFile')


def identity(mutation):
    fields = ('mutatedClass', 'mutatedMethod', 'methodDescription', 'lineNumber', 'mutator', 'description')
    return tuple(mutation.findtext(name, '') for name in fields) + tuple(
        tuple(node.text for node in mutation.iter(tag)) for tag in ('index', 'block'))


def extra_tests(package, classes):
    """ITs outside the package's selection whose source names one of the delta classes (simple name, word match)."""
    prefixes = [p.replace('.*', '').replace('*IT', '').replace('*', '') for p in SELECTIONS[package].split(',')]
    names = [c.rsplit('.', 1)[-1] for c in classes]
    pattern = re.compile(r'\b(' + '|'.join(map(re.escape, names)) + r')\b')
    found = []
    for path in sorted((ROOT / 'src/test/java').rglob('*IT.java')):
        fqcn = str(path.relative_to(ROOT / 'src/test/java'))[:-len('.java')].replace('/', '.')
        if any(fqcn.startswith(prefix) for prefix in prefixes):
            continue
        if pattern.search(path.read_text()):
            found.append(fqcn)
    return found


# 07-05 (payments-3a, attempt 1): PIT refused to start, «1 tests did not pass without mutation»:
# ListFieldsContractIT#CONVENCIONS_API_4_withFieldsEveryUniversalListSendsTheRowIdAndTheRequestedKeysOnly fails in PIT's
# single-JVM coverage run of this selection (it passes in failsafe, 01 and 07-05b). Reported as a defect; left out here.
DROPPED = {'payments': ['com.agilityhub.core.configuration.ListFieldsContractIT']}


def selection(package):
    classes = ['com.agilityhub.core.' + short for name, (pkg, shorts) in BATCHES.items() if pkg == package for short in shorts]
    parts = ['com.agilityhub.core.*Test', *SELECTIONS[package].split(','), *extra_tests(package, classes)]
    return ','.join(part for part in parts if part not in DROPPED.get(package, []))


def plan():
    changes = delta()
    owners = batch_files()
    rows = Counter(source_of(m) for m in ET.parse(GATE).getroot())
    problems = []
    for name, status in sorted(changes.items()):
        if status != 'D' and name[len('com/agilityhub/core/'):] not in owners:
            problems.append(f'changed but in no batch: {name} ({status})')
    for short in owners:
        if 'com/agilityhub/core/' + short not in changes:
            problems.append(f'in a batch but unchanged: {short}')
    print(f'git diff --name-status {BASELINE} HEAD (three packages): {len(changes)} files, '
          + ', '.join(f'{k}={v}' for k, v in sorted(Counter(changes.values()).items())))
    for name, (package, shorts) in BATCHES.items():
        gate_rows = sum(rows['com/agilityhub/core/' + s.replace('.', '/') + '.java'] for s in shorts)
        print(f'{name}: {len(shorts)} classes, {gate_rows} rows in the E11-T06 gate report')
    for package in PACKAGES:
        classes = ['com.agilityhub.core.' + s for n, (p, ss) in BATCHES.items() if p == package for s in ss]
        print(f'{package}: extra ITs naming a delta class: {", ".join(extra_tests(package, classes)) or "none"}')
    deleted = [n for n, s in changes.items() if s == 'D']
    print(f'deleted at HEAD (gate rows dropped, no fresh run): {", ".join(deleted) or "none"}')
    print('\n'.join(problems) or 'every changed file is in exactly one batch')
    return 1 if problems else 0


def run(batch, number):
    package, shorts = BATCHES[batch]
    classes = ['com.agilityhub.core.' + s for s in shorts]
    globs = [glob for clazz in classes for glob in (clazz, clazz + '$*')]
    reports = f'target/pit-reports/e11-t12-{batch}'
    command = [HEAVY, './mvnw', '-q', '-Pmutation', '-DtargetClasses=' + ','.join(globs),
               '-DtargetTests=' + selection(package), '-DmutationUnitSize=50', '-DreportsDirectory=' + reports,
               'test-compile', 'org.pitest:pitest-maven:mutationCoverage']
    name = f'{number}-pit-{batch}'
    paths = {suffix: HERE / (name + suffix) for suffix in ('.command', '.log', '.exit')}
    if any(path.exists() for path in paths.values()):
        sys.exit('Evidence already exists; use a new number')
    paths['.command'].write_text(shlex.join(command) + '\n')
    with paths['.log'].open('x') as log:
        process = subprocess.Popen(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            result = process.wait(timeout=3600)
        except (subprocess.TimeoutExpired, KeyboardInterrupt) as error:
            result = 124 if isinstance(error, subprocess.TimeoutExpired) else 130
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
            log.write(f'\nEvidence runner stopped its command group; exit {result}.\n')
    paths['.exit'].write_text(f'exit {result}\n')
    print(f'{name}: exit {result}', flush=True)
    if result == 0:
        PIT.mkdir(exist_ok=True)
        target = PIT / f'{number}-{batch}-mutations.xml'
        shutil.copyfile(ROOT / reports / 'mutations.xml', target)
        found = {source_of(m) for m in ET.parse(target).getroot()}
        expected = {'com/agilityhub/core/' + s.replace('.', '/') + '.java' for s in shorts}
        assert found <= expected, f'Report holds a class outside the batch: {sorted(found - expected)}'
        statuses = Counter(m.get('status') for m in ET.parse(target).getroot())
        print(f'{target.name}: {sum(statuses.values())} mutations {dict(statuses)}; {len(found)} of {len(expected)} '
              f'classes have mutants; without: ' + ', '.join(sorted(e.rsplit("/", 1)[-1] for e in expected - found)))
    return result


def splice(output, fresh):
    changes = delta()
    owners = batch_files()
    in_batches = {'com/agilityhub/core/' + short for short in owners}
    present = {name for name, status in changes.items() if status != 'D'}
    assert present == in_batches, f'Delta differs from git: missing {sorted(present - in_batches)}, extra {sorted(in_batches - present)}'
    dropped_files = set(changes)
    root = ET.parse(GATE).getroot()
    kept = [m for m in root if source_of(m) not in dropped_files]
    seen = {identity(m) for m in kept}
    assert len(seen) == len(kept), 'Duplicate mutant in the gate report'
    added, reported, runs = [], set(), Counter()
    for path in fresh:
        batch_rows = 0
        for mutation in ET.parse(path).getroot():
            source = source_of(mutation)
            assert source in in_batches, f'{path.name}: {source} is not a delta class'
            key = identity(mutation)
            assert key not in seen, f'{path.name}: mutant reported twice'
            seen.add(key)
            reported.add(source)
            added.append(mutation)
            batch_rows += 1
        runs[path.name] = batch_rows
    covered = {'com/agilityhub/core/' + s.replace('.', '/') + '.java'
               for p in fresh for b, (_, shorts) in BATCHES.items() if f'-{b}-mutations.xml' in p.name for s in shorts}
    assert covered == in_batches, f'Delta files without a fresh run: {sorted(in_batches - covered)}'
    result = ET.Element('mutations')
    result.extend(kept + added)
    ET.ElementTree(result).write(output, encoding='UTF-8', xml_declaration=True)
    print(f'Delta = git diff {BASELINE} HEAD: {len(changes)} source files ({len(present)} present at HEAD); '
          f'{len(root) - len(kept)} gate rows of them dropped; {len(kept)} gate rows kept')
    print(f'{len(added)} fresh rows from {len(fresh)} runs ({", ".join(f"{k}: {v}" for k, v in runs.items())}); '
          f'{len(reported)} delta files have mutants; without mutants: '
          + ', '.join(sorted(s.rsplit('/', 1)[-1] for s in in_batches - reported)))
    print(f'{output.relative_to(ROOT)}: {len(result)} mutations')
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    sub.add_parser('plan')
    r = sub.add_parser('run')
    r.add_argument('batch', choices=BATCHES)
    r.add_argument('number')
    s = sub.add_parser('splice')
    s.add_argument('output', type=Path)
    s.add_argument('fresh', type=Path, nargs='+')
    args = parser.parse_args()
    if args.command == 'plan':
        return plan()
    if args.command == 'run':
        return run(args.batch, args.number)
    return splice(args.output.resolve(), args.fresh)


if __name__ == '__main__':
    sys.exit(main())
