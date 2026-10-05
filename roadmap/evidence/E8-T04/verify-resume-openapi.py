#!/usr/bin/env python3
"""Generate a fresh snapshot and require byte identity with the reviewed contract."""
from pathlib import Path
import subprocess

snapshot = Path('docs/openapi/openapi.json')
before = snapshot.read_bytes()
subprocess.run(['bin/openapi-snapshot'], check=True)
assert snapshot.read_bytes() == before, 'Fresh OpenAPI differs from the reviewed snapshot'
print('PASS bin/openapi-snapshot: fresh snapshot is byte-identical to the reviewed contract')
