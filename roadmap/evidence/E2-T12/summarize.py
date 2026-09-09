"""Summarize the final Maven reports and the additive OpenAPI change."""
from pathlib import Path
import json
import subprocess
import xml.etree.ElementTree as ET

for category in ['surefire', 'failsafe']:
    files = list(Path('target/' + category + '-reports').glob('TEST-*.xml'))
    # Snapshot generation runs separately through Surefire; verify owns it through Failsafe.
    if category == 'surefire':
        files = [p for p in files if not p.name.endswith('.OpenApiSnapshotTest.xml')]
    suites = [ET.parse(p).getroot() for p in files]
    print(category + ':', {key: sum(int(s.get(key, 0)) for s in suites)
                           for key in ['tests', 'failures', 'errors', 'skipped']})
root = ET.parse('target/site/jacoco/jacoco.xml').getroot()
for package in root.findall('package'):
    if package.get('name').startswith('com/agilityhub/core/clubs/content') or package.get('name') == 'com/agilityhub/core/platform/application/definition':
        print(package.get('name') + ':', {
            counter.get('type'): round(100 * int(counter.get('covered')) /
                                      (int(counter.get('covered')) + int(counter.get('missed'))), 2)
            for counter in package.findall('counter') if counter.get('type') in ['LINE', 'BRANCH']})
before = json.loads(subprocess.run(['git', 'show', 'HEAD:docs/openapi/openapi.json'], check=True, capture_output=True, text=True).stdout)
after = json.loads(Path('docs/openapi/openapi.json').read_text())
for group in ['paths', 'schemas']:
    old = before['paths'] if group == 'paths' else before['components']['schemas']
    new = after['paths'] if group == 'paths' else after['components']['schemas']
    print(group, 'removed:', sorted(old.keys() - new.keys()), 'changed:',
          sorted(key for key in old.keys() & new.keys() if old[key] != new[key]),
          'added:', sorted(new.keys() - old.keys()))
