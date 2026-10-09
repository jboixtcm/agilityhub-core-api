#!/usr/bin/env python3
"""Summarize the final clean verification and every test class touched in round 2."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET
ROOT = Path(__file__).resolve().parents[3]
KEYS = ('tests', 'failures', 'errors', 'skipped')
suites = {}
for lane in ('surefire', 'failsafe'):
    totals = dict.fromkeys(KEYS, 0)
    paths = sorted((ROOT / f'target/{lane}-reports').glob('TEST-*.xml'))
    assert paths
    for path in paths:
        suite = ET.parse(path).getroot()
        counts = {key: int(suite.get(key, '0')) for key in KEYS}
        suites[suite.get('name')] = counts, suite
        for key in KEYS:
            totals[key] += counts[key]
    print(lane + ': ' + ', '.join(f'{key}={totals[key]}' for key in KEYS))
    assert totals['failures'] == totals['errors'] == totals['skipped'] == 0
for name in ['configuration.CiWorkflowTest', 'configuration.E8ResponseContractTest', 'payments.api.CapturedPaymentInvariantsIT',
             'clubs.messaging.domain.NotificationCatalogContractTest', 'clubs.messaging.domain.NotificationSpecTest',
             'clubs.messaging.application.engine.MessageTemplateSeedTest', 'clubs.messaging.application.engine.MessageTemplateSeedChecksTest',
             'clubs.messaging.application.engine.NotificationSeedSnapshotTest']:
    name = 'com.agilityhub.core.' + name
    counts, suite = suites[name]
    print(name + ': ' + ', '.join(f'{key}={counts[key]}' for key in KEYS))
    for case in suite.iter('testcase'):
        if 'e8t11_round2' in case.get('name'):
            print('  PASS ' + case.get('name'))
report = (ROOT / 'target/failsafe-reports/TEST-com.agilityhub.core.payments.api.CapturedPaymentInvariantsIT.xml').read_text()
reach = re.findall(r'E8-T11, [^\n]*steps reached: \{[^}]*\}', report)
assert reach
print(reach[-1])
