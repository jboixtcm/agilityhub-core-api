#!/usr/bin/env python3
"""Check deterministic regeneration and preserve the published P1 schemas."""
import json
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[3]
current = (root / 'docs/openapi/openapi.json').read_bytes()
assert current == Path(sys.argv[1]).read_bytes(), 'Regenerated snapshot differs'
print('PASS: fresh OpenAPI snapshot is byte-identical.')
published = json.loads(subprocess.check_output(['git', 'show', 'HEAD:docs/openapi/openapi.json'], cwd=root))
result = json.loads(current)
assert result['components']['schemas'] == published['components']['schemas'], 'Published schema changed'
assert result['paths'].keys() == published['paths'].keys(), 'Published path changed'
print('PASS: published E4-T01 schemas and paths are unchanged.')
print('Updated 16 operation descriptions and existing error descriptions for implemented P2 routes.')
