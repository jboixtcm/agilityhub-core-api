"""Totals of target/surefire-reports and target/failsafe-reports, plus one line per test class (E11-T07 evidence)."""
import glob
import sys
import xml.etree.ElementTree as ET

for kind in ('surefire', 'failsafe'):
    totals = [0, 0, 0, 0]
    rows = []
    for path in sorted(glob.glob(f'target/{kind}-reports/TEST-*.xml')):
        root = ET.parse(path).getroot()
        counts = [int(root.get(key)) for key in ('tests', 'failures', 'errors', 'skipped')]
        totals = [a + b for a, b in zip(totals, counts)]
        rows.append('%s %s tests=%d failures=%d errors=%d skipped=%d' % (kind, root.get('name'), *counts))
    print('%s: classes=%d tests=%d failures=%d errors=%d skipped=%d' % (kind, len(rows), *totals))
    if '--classes' in sys.argv:
        print('\n'.join(rows))
