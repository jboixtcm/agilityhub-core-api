"""Report Maven test counts from the current XML output (use immediately after a run)."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

fields = ('tests', 'failures', 'errors', 'skipped')
changed = {'LifecycleMenuIT', 'DashboardIT', 'E3ContractIT', 'OpenApiSnapshotTest'}
selected_only = '--selected-only' in sys.argv
for kind in (('surefire',) if selected_only else ('surefire', 'failsafe')):
    suites = [ET.parse(p).getroot() for p in sorted(Path(f'target/{kind}-reports').glob('TEST-*.xml'))]
    if selected_only:
        suites = [s for s in suites if s.attrib['name'].split('.')[-1] in changed]
    totals = {key: sum(int(s.attrib.get(key, 0)) for s in suites) for key in fields}
    print(f'{kind}: {len(suites)} classes; ' + ', '.join(f'{key}={value}' for key, value in totals.items()))
    for suite in suites:
        name = suite.attrib['name']
        if name.split('.')[-1] in changed or int(suite.attrib.get('failures', 0)) or int(suite.attrib.get('errors', 0)):
            print(name + ': ' + ', '.join(f'{key}={suite.attrib.get(key, 0)}' for key in fields))
            for case in suite.findall('testcase'):
                for failure in list(case.findall('failure')) + list(case.findall('error')):
                    print('  ' + case.attrib['name'] + ': ' + failure.attrib.get('type', 'failure'))
