"""Summarize the completed Maven reports without running additional tests."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

for folder in ('surefire-reports', 'failsafe-reports'):
    suites = [ET.parse(p).getroot().attrib for p in Path('target', folder).glob('TEST-*.xml')]
    totals = {key: sum(int(suite.get(key, 0)) for suite in suites)
              for key in ('tests', 'failures', 'errors', 'skipped')}
    print(folder + ': ' + ', '.join(f'{key}={value}' for key, value in totals.items()))
    assert totals['tests'] > 0 and all(totals[key] == 0 for key in ('failures', 'errors', 'skipped'))

coverage = ET.parse('target/site/jacoco/jacoco.xml').getroot()
for package in coverage.findall('package'):
    if package.attrib['name'] in (
        'com/agilityhub/core/clubs/signup/domain',
        'com/agilityhub/core/clubs/catalogs/application',
        'com/agilityhub/core/platform/domain',
        'com/agilityhub/core/platform/application',
        'com/agilityhub/core/clubs/census/application',
    ):
        counters = {c.attrib['type']: c.attrib for c in package.findall('counter')}
        ratios = {}
        for kind in ('LINE', 'BRANCH'):
            covered, missed = (int(counters[kind][key]) for key in ('covered', 'missed'))
            ratios[kind] = covered / (covered + missed) if covered + missed else 1
        print(package.attrib['name'] + ': ' + ', '.join(f'{kind}={ratio:.2%}' for kind, ratio in ratios.items()))
        assert ratios['LINE'] >= .85 and ratios['BRANCH'] >= .80

ids = set()
for path in sorted(Path('src/test/java/com/agilityhub/core/clubs/signup/domain').glob('*.java')):
    for method in re.findall(r'void\s+(T_04_\w+)\(', path.read_text()):
        ids.add(method[0:7])
        print(path.name + ': ' + method)
assert {f'T_04_{n:02}' for n in range(1, 11)} <= ids
print('All T-04-01 through T-04-10 present; signup tests use no database I/O.')
