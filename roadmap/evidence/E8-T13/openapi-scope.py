"""Prove E8-T13 adds exactly its three published properties and required entries."""
import copy
import difflib
import json
from pathlib import Path
import subprocess

base = json.loads(subprocess.check_output(['git', 'show', 'b44b2be:docs/openapi/openapi.json']))
current = json.loads(Path('docs/openapi/openapi.json').read_text())
trimmed = copy.deepcopy(current)
expected = {'SavedView': ['system'], 'DashboardCounters': ['pendingInactivityRequests', 'pendingLeaveRequests']}
for schema, fields in expected.items():
    item = trimmed['components']['schemas'][schema]
    for field in fields:
        assert field not in base['components']['schemas'][schema]['properties']
        prop = item['properties'].pop(field)
        assert field in item['required']
        item['required'].remove(field)
        if field == 'system':
            assert prop['type'] == 'boolean' and prop['readOnly'] is True
        else:
            assert prop['type'] == 'integer' and prop['format'] == 'int32'
assert trimmed == base, 'Unrelated OpenAPI change'
print('PASS: exactly SavedView.system (read-only boolean), DashboardCounters.pendingInactivityRequests and pendingLeaveRequests (int32).')
print('Existing schemas, properties, operations, request bodies and response metadata are unchanged.')
print(''.join(difflib.unified_diff(json.dumps(base, indent=2).splitlines(True), json.dumps(current, indent=2).splitlines(True), fromfile='baseline', tofile='E8-T13')), end='')
