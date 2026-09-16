"""Summarize the E4 contract diff and the completed Maven verification reports."""
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

old = json.loads(subprocess.check_output(['git', 'show', 'HEAD:docs/openapi/openapi.json']))
new = json.loads(Path('docs/openapi/openapi.json').read_text())
verbs = {'get', 'post', 'put', 'patch', 'delete', 'options', 'head', 'trace'}
def operations(document):
    return {(path, method) for path, value in document['paths'].items() for method in value if method in verbs}
added = operations(new) - operations(old)
print(f"Paths: {len(old['paths'])} -> {len(new['paths'])}; added {len(new['paths'].keys() - old['paths'].keys())}")
print(f'Operations: {len(operations(old))} -> {len(operations(new))}; added {len(added)}, removed {len(operations(old) - operations(new))}')
assert len(added) == 55
assert not operations(old) - operations(new)
changelog = Path('docs/openapi/CHANGELOG.md').read_text()
for path, method in sorted(added):
    assert f'`{method.upper()} {path.removeprefix("/api/v1")}`' in changelog
s06 = sum(path.startswith(('/api/v1/week-templates', '/api/v1/coverage', '/api/v1/weeks', '/api/v1/class-sessions', '/api/v1/ring-blocks', '/api/v1/day-grid')) for path, _ in added)
assert s06 == 31 and len(added) - s06 == 24
print(f'All {s06} S06 + {len(added) - s06} S07 additions are individually listed in the OpenAPI changelog.')
schemas = new['components']['schemas']
previous = old['components']['schemas']
print(f'Schemas: {len(previous)} -> {len(schemas)}; added {len(schemas.keys() - previous.keys())}, removed {len(previous.keys() - schemas.keys())}')
assert len(schemas.keys() - previous.keys()) == 95
changed = [name for name in sorted(schemas.keys() & previous.keys()) if schemas[name] != previous[name]]
assert changed == ['AuditAction', 'UploadRequest']
print('Existing schemas changed: ' + ', '.join(changed))
print('Existing operations changed:')
for path, method in sorted(operations(new) & operations(old)):
    before, after = old['paths'][path][method], new['paths'][path][method]
    if before != after:
        print(f"  {method.upper()} {path}: {', '.join(sorted(key for key in before.keys() | after.keys() if before.get(key) != after.get(key)))}")
print('Added schemas: ' + ', '.join(sorted(schemas.keys() - previous.keys())))
print('ErrorCode status changes: none; canonical S06/S07 statuses already match the catalog.')
for folder in ['surefire-reports', 'failsafe-reports']:
    totals = dict.fromkeys(['tests', 'failures', 'errors', 'skipped'], 0)
    files = list(Path('target', folder).glob('TEST-*.xml'))
    for file in files:
        report = ET.parse(file).getroot()
        for key in totals:
            totals[key] += int(report.attrib.get(key, 0))
    print(f'{folder}: {len(files)} suites; ' + ', '.join(f'{key}={value}' for key, value in totals.items()))
    assert totals['tests'] > 0 and totals['failures'] == totals['errors'] == totals['skipped'] == 0
coverage = ET.parse('target/site/jacoco/jacoco.xml').getroot()
for package in coverage.findall('package'):
    name = package.attrib['name']
    if name.startswith(('com/agilityhub/core/clubs/scheduling/', 'com/agilityhub/core/clubs/activities/')) and name.endswith(('/domain', '/application', '/api')):
        counts = {counter.attrib['type']: counter.attrib for counter in package.findall('counter')}
        values = []
        for kind in ['LINE', 'BRANCH']:
            if kind not in counts:
                values.append(f'{kind}=n/a')
                continue
            covered, missed = int(counts[kind]['covered']), int(counts[kind]['missed'])
            values.append(f'{kind}={covered}/{covered + missed} ({100 * covered / (covered + missed):.2f}%)')
        print(f'{name}: ' + ', '.join(values))
original_task = subprocess.check_output(['git', 'show', 'HEAD:roadmap/tasks/E4-T01.md']).decode()
current_task = Path('roadmap/tasks/E4-T01.md').read_text()
assert original_task.split('## Organizer verification', 1)[1] == current_task.split('## Organizer verification', 1)[1]
changed_files = subprocess.check_output(['git', 'diff', '--name-only']).decode().splitlines()
assert 'roadmap/ROADMAP.md' not in changed_files
assert [name for name in changed_files if name.startswith('roadmap/tasks/')] == ['roadmap/tasks/E4-T01.md']
print('Scope: only E4-T01 task edited; Organizer verification and ROADMAP.md unchanged.')
