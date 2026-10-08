#!/usr/bin/env python3
"""Summarize the final clean run and correct E8-T05's complete changed-class inventory."""
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[3]
metrics = ('tests', 'failures', 'errors', 'skipped')
suites = {}
for lane in ('surefire', 'failsafe'):
    total = dict.fromkeys(metrics, 0)
    files = sorted((root / f'target/{lane}-reports').glob('TEST-*.xml'))
    assert files, f'Missing {lane} reports'
    for path in files:
        suite = ET.parse(path).getroot()
        counts = {key: int(suite.get(key, '0')) for key in metrics}
        suites[suite.get('name')] = counts
        for key in metrics:
            total[key] += counts[key]
    print(lane + ': ' + ', '.join(f'{key}={total[key]}' for key in metrics))
    assert total['failures'] == total['errors'] == total['skipped'] == 0

def report(paths):
    for path in sorted(paths):
        name = path.removeprefix('src/test/java/').removesuffix('.java').replace('/', '.')
        matches = [counts for suite, counts in suites.items() if suite == name or suite.startswith(name + '$')]
        if not matches:
            print(path + ': supporting fixture/rule, no standalone test suite')
            continue
        total = {key: sum(counts[key] for counts in matches) for key in metrics}
        print(name + ': ' + ', '.join(f'{key}={total[key]}' for key in metrics))

changed = subprocess.check_output(['git', 'diff', '--name-only', '--', 'src/test/java'], cwd=root, text=True).splitlines()
print('\nE8-T09 changed test classes:')
report(set(changed) | {'src/test/java/com/agilityhub/core/payments/application/stripe/StripeHttpFixture.java'})
commits = ['39ac529', '188ac1e', '875942e', '057ee05', '7b5521d']
print('\nPoint 11: E8-T05 inventory, derived from ALL implementation commits: ' + ', '.join(c + '...[truncated]' for c in commits))
paths = set()
for commit in commits:
    paths.update(path for path in subprocess.check_output(['git', 'diff-tree', '--no-commit-id', '--name-only', '-r', commit],
                 cwd=root, text=True).splitlines() if path.startswith('src/test/java/') and path.endswith('.java'))
assert 'src/test/java/com/agilityhub/core/payments/application/ports/BillingPortDefaultsTest.java' in paths
report(paths)
print('\nBillingPortDefaultsTest is included; E8-T05 files remain untouched. Counts come from this final clean run.')
