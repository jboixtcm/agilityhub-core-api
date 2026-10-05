#!/usr/bin/env python3
"""Prove the real admin-refund race test rejects lost provenance, then restore the exact source."""
from pathlib import Path
import subprocess
import sys

path = Path('src/main/java/com/agilityhub/core/payments/application/PaymentRefunds.java')
original = path.read_text()
needle = 'String reason = operation == null ? stripeReason : operation.reason();\n        String actor = operation == null ? null : operation.actorId();'
assert needle in original
command = ['./mvnw', '-q', '-Dmaven.repo.local=/private/tmp/agilityhub-e8-t04-m2',
           '-Dit.test=CardPaymentsIT#T_12_17_refundMetadataTargetsSecondRowBeforeProviderResultAndKeepsAdminProvenance',
           '-DfailIfNoTests=false', 'test-compile', 'failsafe:integration-test', 'failsafe:verify']
try:
    path.write_text(original.replace(needle, 'String reason = stripeReason;\n        String actor = null;'))
    result = subprocess.run([sys.executable, 'roadmap/evidence/E8-T04/run-bounded.py',
                             'roadmap/evidence/E8-T04/67-round2-provenance-red.log', *command]).returncode
    print(f'Provenance removed; targeted Maven exit={result} (expected 1).')
finally:
    path.write_text(original)
    print('Exact production source restored.')
if result != 1:
    raise SystemExit('The provenance mutation was not rejected as expected')
