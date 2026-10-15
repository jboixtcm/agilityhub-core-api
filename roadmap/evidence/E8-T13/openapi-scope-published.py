"""Prove the published E8-T13 commit changed exactly its three OpenAPI properties, and that HEAD's snapshot still carries them."""
import copy
import difflib
import json
from pathlib import Path
import subprocess

PUBLISHED = '07f16ab6'


def snapshot(rev):
    return json.loads(subprocess.check_output(['git', 'show', f'{rev}:docs/openapi/openapi.json']))


base = snapshot(PUBLISHED + '^')
published = snapshot(PUBLISHED)
current = json.loads(Path('docs/openapi/openapi.json').read_text())
expected = {'SavedView': ['system'], 'DashboardCounters': ['pendingInactivityRequests', 'pendingLeaveRequests']}


def check_fields(doc, label):
    trimmed = copy.deepcopy(doc)
    for schema, fields in expected.items():
        item = trimmed['components']['schemas'][schema]
        for field in fields:
            assert field not in base['components']['schemas'][schema]['properties']
            prop = item['properties'].pop(field)
            assert field in item['required'], f'{label}: {schema}.{field} not required'
            item['required'].remove(field)
            if field == 'system':
                assert prop['type'] == 'boolean' and prop['readOnly'] is True, f'{label}: {prop}'
            else:
                assert prop['type'] == 'integer' and prop['format'] == 'int32', f'{label}: {prop}'
    return trimmed


assert check_fields(published, PUBLISHED) == base, 'Unrelated OpenAPI change in the E8-T13 commit'
check_fields(current, 'HEAD')
for schema in ('SavedView', 'DashboardCounters'):
    assert current['components']['schemas'][schema] == published['components']['schemas'][schema], f'HEAD changed {schema}'
print(f'PASS: {PUBLISHED}^ -> {PUBLISHED} adds exactly SavedView.system (read-only boolean), '
      'DashboardCounters.pendingInactivityRequests and pendingLeaveRequests (int32), all required.')
print('PASS: HEAD docs/openapi/openapi.json keeps SavedView and DashboardCounters identical to the published commit.')
print(''.join(difflib.unified_diff(json.dumps(base, indent=2, sort_keys=True).splitlines(True),
                                   json.dumps(published, indent=2, sort_keys=True).splitlines(True),
                                   fromfile=PUBLISHED + '^', tofile=PUBLISHED)), end='')
