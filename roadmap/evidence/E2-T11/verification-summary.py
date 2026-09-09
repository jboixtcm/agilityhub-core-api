"""Summarize the fresh Maven reports and verify the committed API snapshot."""
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
for folder in ['surefire-reports', 'failsafe-reports']:
    totals = dict.fromkeys(['tests', 'failures', 'errors', 'skipped'], 0)
    files = list(Path('target', folder).glob('TEST-*.xml'))
    assert files, folder
    for file in files:
        suite = ET.parse(file).getroot()
        for key in totals:
            totals[key] += int(suite.attrib.get(key, 0))
    print(folder, totals)
    assert totals['tests'] and not any(totals[key] for key in ['failures', 'errors', 'skipped'])
report = ET.parse('target/site/jacoco/jacoco.xml').getroot()
for package in report.findall('package'):
    name = package.attrib['name']
    counters = {row.attrib['type']: (int(row.attrib['covered']), int(row.attrib['missed'])) for row in package.findall('counter')}
    if name.endswith(('/domain', '/application')) and any(context in name for context in ['catalogs', 'census', 'identity', 'migration', 'platform']):
        print(name, {key: f'{100 * value[0] / sum(value):.2f}%' if sum(value) else 'n/a' for key, value in counters.items() if key in ['LINE', 'BRANCH']})
subprocess.run(['cmp', 'target/openapi.json', 'docs/openapi/openapi.json'], check=True)
print('OpenAPI: byte-for-byte match')
subprocess.run(['git', 'diff', '--check'], check=True)
print('git diff --check: clean (read-only)')
