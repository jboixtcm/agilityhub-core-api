from pathlib import Path
import sys
checks = {
    'step 7: rollback spec id is R_12_19_R_12_14': 'void R_12_19_R_12_14_rolledBackComesFromTheRunAndTheAdminsReasonIsFreeText' in Path('src/test/java/com/agilityhub/core/payments/api/BillingCycleIT.java').read_text(),
    'step 9: restore runbook names the required index and restart': all(word in Path('docs/DEPLOY.md').read_text() for word in ['invoice_club_series_number_all', '--noIndexRestore', 'ensureIndexes']),
    'step 9: numbering diagnostics gated by evidence property': 'Boolean.getBoolean("agilityhub.evidence")' in Path('src/test/java/com/agilityhub/core/payments/api/InvoiceNumberingIT.java').read_text(),
}
for name, passed in checks.items(): print(('PASS ' if passed else 'FAIL ') + name)
sys.exit(0 if all(checks.values()) else 1)
