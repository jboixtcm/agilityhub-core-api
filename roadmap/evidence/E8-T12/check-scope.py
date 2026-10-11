#!/usr/bin/env python3
"""Read-only scope, evidence-retention and report-boundary checks for E8-T12."""
from pathlib import Path
import ast
import subprocess

root = Path(__file__).resolve().parents[3]
paths = subprocess.check_output(['git', 'diff', '--name-only'], cwd=root, text=True).splitlines()
paths += subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard'], cwd=root, text=True).splitlines()
allowed = {'CHANGELOG.md', 'roadmap/STATUS.md', 'roadmap/MESSAGES.md', 'roadmap/tasks/E8-T12.md',
    'src/main/java/com/agilityhub/core/payments/application/PaymentRefunds.java',
    'src/main/java/com/agilityhub/core/payments/domain/CapturedPaymentLedger.java',
    'src/main/java/com/agilityhub/core/payments/persistence/PaymentOperationRepository.java',
    'src/main/java/com/agilityhub/core/payments/persistence/StripeRefundRepository.java',
    'src/main/java/com/agilityhub/core/payments/persistence/UpfrontPaymentRepository.java',
    'src/test/java/com/agilityhub/core/payments/api/CapturedPaymentInvariantsIT.java'}
unexpected = {path for path in paths if path not in allowed and not path.startswith('roadmap/evidence/E8-T12/')}
assert not unexpected, unexpected
old = subprocess.check_output(['git', 'show', 'HEAD:roadmap/tasks/E8-T12.md'], cwd=root, text=True)
report = (root / 'roadmap/tasks/E8-T12.md').read_text()
assert old.split('## Organizer verification', 1)[1] == report.split('## Organizer verification', 1)[1]
assert len(report.encode()) < 120 * 1024
print('Scope: only E8-T12 product/test files, changelog and permitted roadmap records changed.')
print('Organizer verification unchanged; ROADMAP, other tasks and OpenAPI unchanged.')
print('Task report bytes:', len(report.encode()))
for path in sorted(Path(__file__).parent.glob('*.py')):
    ast.parse(path.read_text())
logs = sorted(Path(__file__).parent.glob('*.log'))
for path in logs:
    result = subprocess.run(['git', 'check-ignore', '--quiet', str(path.relative_to(root))], cwd=root)
    assert result.returncode == 1, str(path) + ' is ignored'
print('Evidence logs included by repository ignore policy:', len(logs))
print('Evidence scripts parse successfully.')
