#!/usr/bin/env python3
"""Round 2: summarize the completed clean verification, each changed test class and each round-2 test case."""
from pathlib import Path
import xml.etree.ElementTree as ET
import sys

target = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("target")
if len(sys.argv) > 2:
    exit_code = int(Path(sys.argv[2]).read_text())
    print(f"Clean verification exit: {exit_code}")
    assert exit_code == 0, "Clean verification did not pass"

changed = {'E10ContractIT', 'E10ResponseContractTest', 'PlatformClubContractGuardsTest', 'OpenApiSnapshotTest'}
round2 = {
    'T_17_03_R_17_03_domainPublishesStableTxtChallengeInstructions',
    'T_17_05_R_17_05_providerReadContractKeepsEditableNonSecretConfiguration',
    'T_17_05_R_17_05_providerReadsAndWritesProjectOnlyNonSecretSettings',
    'T_17_10_R_17_12_clubPagePublishesAppliedFiltersLikeListPage',
    'T_17_10_R_17_12_clubPageReturnsTheAppliedUniversalFilters',
    'T_17_12_R_17_12_platformLogoUploadReusesAttachmentResponseAndPurposes',
    'T_17_12_R_17_12_platformLogoUploadValidatesBeforeNoWriteStub',
    'T_17_12_R_17_12_uploadUsesTargetClubImageTypesAndExactSizeLimit',
    'T_17_02_R_17_02_definitionErrorsNameNestedFieldsWithoutInputValues',
}
seen, cases = set(), {}
for kind in ('surefire', 'failsafe'):
    totals = dict.fromkeys(('tests', 'failures', 'errors', 'skipped'), 0)
    rows = []
    for path in sorted((target / (kind + '-reports')).glob('TEST-*.xml')):
        root = ET.parse(path).getroot()
        values = {key: int(root.get(key, '0')) for key in totals}
        for key, value in values.items():
            totals[key] += value
        name = root.get('name', '').rsplit('.', 1)[-1]
        if name in changed:
            seen.add(name)
            rows.append('  ' + name + ': ' + ' '.join(f'{key}={value}' for key, value in values.items()))
        for case in root.iter('testcase'):
            method = case.get('name', '').split('(')[0]
            if method in round2:
                bad = [child.tag for child in case if child.tag in ('failure', 'error', 'skipped')]
                cases[method] = name + ' ' + ('passed' if not bad else ','.join(bad))
    print(kind + ' totals: ' + ' '.join(f'{key}={value}' for key, value in totals.items()))
    print('\n'.join(rows))
    assert totals['tests'] > 0, kind + ': no report'
    assert totals['failures'] == totals['errors'] == totals['skipped'] == 0, kind + ': not completely green'
print('round-2 cases:')
for method in sorted(round2):
    print('  ' + method + ': ' + cases.get(method, 'MISSING'))
assert seen == changed, 'Missing changed classes: ' + str(changed - seen)
assert set(cases) == round2 and all(value.endswith('passed') for value in cases.values()), 'Round-2 case missing or not passed'
