#!/usr/bin/env python3
"""E8-T01 round 2: what the working tree's docs/openapi/openapi.json changes against HEAD (read-only `git show`)."""
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
before = json.loads(subprocess.run(['git', 'show', 'HEAD:docs/openapi/openapi.json'], cwd=ROOT, check=True, capture_output=True).stdout)
after = json.loads((ROOT / 'docs/openapi/openapi.json').read_text())

def operations(api):
    return {f'{method.upper()} {path}': op for path, item in api['paths'].items() for method, op in item.items()}

ops_before, ops_after = operations(before), operations(after)
print('operations added', sorted(set(ops_after) - set(ops_before)), 'removed', sorted(set(ops_before) - set(ops_after)))
for key in sorted(set(ops_before) & set(ops_after)):
    a, b = ops_before[key], ops_after[key]
    changes = []
    if a.get('description') != b.get('description'):
        changes.append('description')
    for status in sorted(set(a.get('responses', {})) | set(b.get('responses', {}))):
        da = a.get('responses', {}).get(status, {}).get('description')
        db = b.get('responses', {}).get(status, {}).get('description')
        if da != db:
            changes.append(f'{status}: {da!r} -> {db!r}')
    if a.get('parameters') != b.get('parameters') or a.get('requestBody') != b.get('requestBody') or a.get('security') != b.get('security'):
        changes.append('parameters/body/security')
    if changes:
        print('op changed', key, '|', '; '.join(changes))
schemas_before, schemas_after = before['components']['schemas'], after['components']['schemas']
print('schemas added', sorted(set(schemas_after) - set(schemas_before)), 'removed', sorted(set(schemas_before) - set(schemas_after)))
for name in sorted(set(schemas_before) & set(schemas_after)):
    a, b = schemas_before[name], schemas_after[name]
    if a == b:
        continue
    props_a, props_b = a.get('properties', {}), b.get('properties', {})
    detail = []
    for prop in sorted(set(props_a) | set(props_b)):
        if props_a.get(prop) != props_b.get(prop):
            detail.append(f"{prop}: type {props_a.get(prop, {}).get('type')} -> {props_b.get(prop, {}).get('type')}")
    if a.get('required') != b.get('required'):
        detail.append(f"required {a.get('required')} -> {b.get('required')}")
    if a.get('description') != b.get('description'):
        detail.append('description')
    print('schema changed', name, '|', '; '.join(detail))
