#!/usr/bin/env python3
"""Extract the actual curl/provider/event proof from the final clean run."""
from pathlib import Path
import os
import re
import sys

source = Path(sys.argv[1])
lines = source.read_text().splitlines()
prefixes = ('curl -X ', 'FakePaymentProvider operation=', 'StripeEvent eventId=', 'PASS curl sequence:')
proof = [line for line in lines if line.startswith(prefixes)]
assert sum(line.startswith('PASS curl sequence:') for line in proof) == 1, 'Missing completed curl proof'
assert sum(line.startswith('FakePaymentProvider operation=') for line in proof) == 3
assert sum(line.startswith('StripeEvent eventId=') for line in proof) == 4
print(f'Actual proof from {source}:')
print('\n'.join(proof))
print('\nExecutable task regression methods:')
for relative in ('payments/api/CardPaymentsIT.java', 'payments/api/PaymentCurlIT.java',
                 'payments/application/stripe/StripePaymentProviderTest.java',
                 'payments/application/stripe/StripeCallsTest.java', 'configuration/E8ContractIT.java'):
    path = Path('src/test/java/com/agilityhub/core', relative)
    for name in re.findall(r'\bvoid\s+(T_12_\w+)\s*\(', path.read_text()):
        print(f'{path.name}: {name}')
print('\nExternal credentials (presence only):')
for key in ('STRIPE_TEST_SECRET_KEY', 'STRIPE_TEST_WEBHOOK_SECRET'):
    print(f'{key}: {"present" if os.environ.get(key) else "absent"}')
