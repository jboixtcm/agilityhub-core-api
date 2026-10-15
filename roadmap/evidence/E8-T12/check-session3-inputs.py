#!/usr/bin/env python3
"""Prove retained red evidence still covers the unchanged final E107 regressions."""
from pathlib import Path
import hashlib
import json
import subprocess

evidence = Path(__file__).resolve().parent
root = evidence.parents[2]
prior = json.loads((evidence / 'round5-final-verification-inputs.json').read_text())
for name in (
    'src/main/java/com/agilityhub/core/payments/application/PaymentRefunds.java',
    'src/main/java/com/agilityhub/core/payments/domain/PaymentIntentLedger.java',
    'src/main/java/com/agilityhub/core/payments/persistence/UpfrontPaymentRepository.java',
    'src/test/java/com/agilityhub/core/payments/api/CapturedPaymentInvariantsIT.java',
    'src/test/java/com/agilityhub/core/payments/domain/PaymentIntentLedgerTest.java',
):
    expected = ''.join(prior[name])
    assert hashlib.sha256((root / name).read_bytes()).hexdigest() == expected, name
    print('Unchanged since final counterfactual 95:', name)

current = json.loads((evidence / 'round5-session3-verification-inputs.json').read_text())
names = subprocess.check_output(['git', 'ls-files', '-co', '--exclude-standard', '-z'], cwd=root).decode().split('\0')
inputs = {name for name in names if name and not name.startswith('roadmap/')
          and name != 'CHANGELOG.md' and (root / name).is_file()}
assert inputs == set(current), 'Verification input paths were added or removed'
print(f'Current input inventory matches all {len(inputs)} snapshot paths, including documentation.')

original = subprocess.check_output(['git', 'show', 'HEAD:roadmap/tasks/E8-T12.md'], cwd=root, text=True)
old_report = original.split('## Executor report\n', 1)[1].split('## Organizer verification', 1)[0]
assert (evidence / 'executor-report-rounds1-5-through-session2.md').read_text() == '## Executor report\n' + old_report
print('Earlier Executor report archived verbatim; no evidence removed.')
