#!/usr/bin/env python3
"""E8-T01 round 2: count token, key, webhook-secret, bank-key and IBAN patterns in this task's evidence logs (all must be 0),
and list the e-mail addresses they contain (fictional @example.test only)."""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
PATTERN = re.compile(r'eyJ[A-Za-z0-9_-]{10,}|sk_(live|test)_[A-Za-z0-9]{6,}|whsec_[A-Za-z0-9]{6,}|BILLING_(BANK|SECRETS)_KEY=[A-Za-z0-9+/]{8,}|ES[0-9]{2}[0-9 ]{16,}')
EMAIL = re.compile(r'[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}')
emails = set()
for log in sorted((ROOT / 'roadmap/evidence/E8-T01').glob('*.log')):
    text = log.read_text(errors='replace')
    print(f'{log.name}: {len(PATTERN.findall(text))}')
    emails.update(EMAIL.findall(text))
print('e-mail addresses:', sorted(emails))
