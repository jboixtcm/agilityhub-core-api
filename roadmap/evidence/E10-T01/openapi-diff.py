#!/usr/bin/env python3
"""Ensure the snapshot only adds the S17 contract; existing operations/schemas stay identical."""
import json
from pathlib import Path
import subprocess
import sys

before = json.loads(subprocess.check_output(['git', 'show', (sys.argv[1] if len(sys.argv)>1 else '92f5bb6e') + ':docs/openapi/openapi.json']))
after = json.loads(Path('docs/openapi/openapi.json').read_text())
methods = {'get', 'post', 'put', 'patch', 'delete', 'head', 'options'}
def operations(document):
    return {(method.upper(), path): value for path, item in document['paths'].items()
            for method, value in item.items() if method in methods}
old, new = operations(before), operations(after)
removed, added = set(old) - set(new), set(new) - set(old)
changed = [key for key in old.keys() & new.keys() if old[key] != new[key]]
expected = {(route['method'], route['path']) for route in json.loads(Path('src/test/resources/fixtures/contracts/e10-routes.json').read_text())}
expected.add(('GET', '/internal/domains/allowed'))
print(f'Operations: {len(old)} -> {len(new)}; added={len(added)} removed={len(removed)} changed={len(changed)}')
for method, path in sorted(added):
    print(f'  + {method} {path}')
assert not removed and not changed and added == expected, (removed, changed, added ^ expected)
old_s, new_s = before['components']['schemas'], after['components']['schemas']
changed_s = [key for key in old_s if old_s[key] != new_s.get(key)]
print(f'Schemas: {len(old_s)} -> {len(new_s)}; added={len(set(new_s)-set(old_s))}; changed/removed={changed_s}')
assert not changed_s, changed_s
for key in ('openapi', 'info', 'servers', 'security'):
    assert before.get(key) == after.get(key), key
print('S17-only OpenAPI diff: PASS')
