"""E7-T03 evidence: the totals of target/surefire-reports and target/failsafe-reports after `./mvnw -q clean verify`, plus one
line per test class this task adds or changes, and the JaCoCo line/branch ratios of the messaging packages.

Run from the repository root: python3 roadmap/evidence/E7-T03/test_summary.py"""
import csv
import glob
import os
import xml.etree.ElementTree as ET

CLASSES = [
    'MessageTemplateSeedTest', 'MessageTemplateSeedChecksTest', 'TemplateValidatorTest', 'MessagingE7T03UnitTest', 'NotificationSeedSnapshotTest',
    'RiskNotificationTextsTest', 'NoShowNotificationTextsTest', 'FollowupNotificationTextsTest', 'OpenApiRequiredContractTest', 'MessageParityTest',
    'NotificationCatalogContractTest', 'ErrorCatalogContractTest', 'AuditContractTest', 'ArchitectureTest', 'E7ResponseContractTest', 'EventCatalogContractTest',
    'MessageTemplatesIT', 'NotificationFeedIT', 'NotificationLogIT', 'NotificationPreferencesIT', 'PushSubscriptionsIT', 'InfoScreenIT', 'E7ContractIT',
    'E2ContractIT', 'ListFieldsContractIT', 'NotificationEngineIT', 'NotificationDispatcherIT', 'NoShowNoticesJobIT', 'FollowupIT', 'WaitlistIT',
    'E7PersistenceIT', 'EmailUnsubscribeIT', 'DemoSeedsIT', 'OpenApiSnapshotTest',
    # Round 2
    'TemplateProviderTest', 'MessagingDocumentsTest', 'ExportEngineIT',
]


def totals(directory):
    tests = failures = errors = skipped = 0
    per_class = {}
    for path in glob.glob(os.path.join(directory, 'TEST-*.xml')):
        suite = ET.parse(path).getroot()
        t, f, e, s = (int(suite.get(k, 0)) for k in ('tests', 'failures', 'errors', 'skipped'))
        tests += t; failures += f; errors += e; skipped += s
        per_class[suite.get('name').rsplit('.', 1)[-1]] = (t, f, e, s)
    return (tests, failures, errors, skipped), per_class


for name, directory in (('surefire (unit)', 'target/surefire-reports'), ('failsafe (integration)', 'target/failsafe-reports')):
    (t, f, e, s), per_class = totals(directory)
    print(f'{name}: tests={t} failures={f} errors={e} skipped={s}')
    for cls in CLASSES:
        if cls in per_class:
            ct, cf, ce, cs = per_class[cls]
            print(f'  {cls}: tests={ct} failures={cf} errors={ce} skipped={cs}')

if os.path.exists('target/site/jacoco/jacoco.csv'):
    rows = {}
    with open('target/site/jacoco/jacoco.csv', encoding='utf-8') as f:
        for row in csv.DictReader(f):
            package = row['PACKAGE']
            if 'clubs.messaging' in package or package.endswith('clubs.census.application') or package.endswith('platform.application.definition') \
                    or package.endswith('clubs.common.application'):
                agg = rows.setdefault(package, [0, 0, 0, 0])
                agg[0] += int(row['LINE_MISSED']); agg[1] += int(row['LINE_COVERED'])
                agg[2] += int(row['BRANCH_MISSED']); agg[3] += int(row['BRANCH_COVERED'])
    print('JaCoCo (merged unit + integration), line / branch covered ratio:')
    for package, (lm, lc, bm, bc) in sorted(rows.items()):
        line = lc / (lm + lc) if lm + lc else 1.0
        branch = bc / (bm + bc) if bm + bc else 1.0
        print(f'  {package}: lines {line:.3f} ({lc}/{lm + lc}), branches {branch:.3f} ({bc}/{bm + bc})')
