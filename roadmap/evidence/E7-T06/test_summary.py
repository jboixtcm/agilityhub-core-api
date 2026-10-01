"""E7-T06 evidence: the totals of target/surefire-reports and target/failsafe-reports after `./mvnw -q clean verify`, one line
per test class this task adds or changes, and the JaCoCo line/branch ratios of the packages it touches.

Run from the repository root: python3 roadmap/evidence/E7-T06/test_summary.py"""
import csv
import glob
import os
import xml.etree.ElementTree as ET

CLASSES = [
    # added
    'MessagingE7T06UnitTest', 'TemplateUpgradeIT', 'TemplateVariableParityIT',
    # changed
    'MessageTemplateSeedTest', 'TemplateValidatorTest', 'NotificationSeedSnapshotTest', 'NotificationPreferencesIT',
    # round 2 (01-10): changed
    'AuditWriterTest', 'SignupSecurityFixesIT', 'E4ResponseContractTest',
    # the neighbours they share code with
    'MessagingE7T03UnitTest', 'NotificationEngineIT', 'MessageTemplatesIT', 'NotificationCatalogContractTest', 'ArchitectureTest',
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
    classes = {}
    with open('target/site/jacoco/jacoco.csv', encoding='utf-8') as f:
        for row in csv.DictReader(f):
            package = row['PACKAGE']
            if 'clubs.messaging' in package or package.endswith('clubs.census.application') or package.endswith('platform.application.audit'):
                agg = rows.setdefault(package, [0, 0, 0, 0])
                agg[0] += int(row['LINE_MISSED']); agg[1] += int(row['LINE_COVERED'])
                agg[2] += int(row['BRANCH_MISSED']); agg[3] += int(row['BRANCH_COVERED'])
            if row['CLASS'] in ('TemplateUpgrade', 'NotificationPreferencesService', 'CensusNotificationFacts', 'MessageTemplateService', 'TemplateValidator',
                                'AuditWriter', 'TemplatePreviewService', 'SignupService') \
                    or row['CLASS'].startswith('TemplateUpgrade.') or row['CLASS'] == 'NotificationEngine':
                classes[row['CLASS']] = (int(row['LINE_MISSED']), int(row['LINE_COVERED']), int(row['BRANCH_MISSED']), int(row['BRANCH_COVERED']))
    print('JaCoCo (merged unit + integration), line / branch covered ratio:')
    for package, (lm, lc, bm, bc) in sorted(rows.items()):
        line = lc / (lm + lc) if lm + lc else 1.0
        branch = bc / (bm + bc) if bm + bc else 1.0
        print(f'  {package}: lines {line:.3f} ({lc}/{lm + lc}), branches {branch:.3f} ({bc}/{bm + bc})')
    for cls, (lm, lc, bm, bc) in sorted(classes.items()):
        print(f'  class {cls}: lines {lc}/{lm + lc}, branches {bc}/{bm + bc}')
