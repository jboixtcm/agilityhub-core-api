#!/usr/bin/env python3
"""Redact credential-shaped values and full hashes from task evidence, retaining each attempt."""
from pathlib import Path
import re
import sys

for arg in sys.argv[1:]:
    p = Path(arg)
    original = p.read_text(errors='replace')
    value = re.sub(r'eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{10,}', 'eyJ...[truncated]', original)
    value = re.sub(r'\b(sk_live_|sk_test_|whsec_)[A-Za-z0-9]{12,}', lambda m: m.group(1)+'...[truncated]', value)
    value = re.sub(r'(?<=ipHash=)[A-Za-z0-9_-]{20,}', lambda m: m.group()[:8]+'...[truncated]', value)
    value = re.sub(r'\b[0-9a-fA-F]{40,}\b', lambda m: m.group()[:8]+'...[truncated]', value)
    if value != original:
        p.write_text(value)
        print(p.name + ': redacted full hashes or credential-shaped values')
print('Evidence redaction complete; no values printed.')
