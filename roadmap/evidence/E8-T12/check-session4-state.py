#!/usr/bin/env python3
"""Check saved regression identity, inherited attempt state and session scope."""
from pathlib import Path
import hashlib
import json
import subprocess

root = Path(__file__).resolve().parents[3]
evidence = Path(__file__).resolve().parent
prior = json.loads((evidence / "round5-final-verification-inputs.json").read_text())
paths = (
    "src/main/java/com/agilityhub/core/payments/application/PaymentRefunds.java",
    "src/main/java/com/agilityhub/core/payments/domain/PaymentIntentLedger.java",
    "src/main/java/com/agilityhub/core/payments/persistence/UpfrontPaymentRepository.java",
    "src/test/java/com/agilityhub/core/payments/api/CapturedPaymentInvariantsIT.java",
    "src/test/java/com/agilityhub/core/payments/domain/PaymentIntentLedgerTest.java",
)
for path in paths:
    assert hashlib.sha256((root / path).read_bytes()).hexdigest() == "".join(prior[path]), path
    print("Unchanged since final counterfactual 95:", path)
stem = evidence / "124-round5-session3-clean-verify"
print("Inherited attempt 124 has recorded exit:", stem.with_suffix(".exit").exists())
print("Inherited isolated verification directory exists:", Path("/private/tmp/e8-t12-round5-session3-20261015").exists())
print("Incomplete attempt 124 is not accepted as verification success.")
original = subprocess.check_output(["git", "show", "HEAD:roadmap/tasks/E8-T12.md"], text=True)
current = (root / "roadmap/tasks/E8-T12.md").read_text()
assert original.split("## Organizer verification", 1)[1] == current.split("## Organizer verification", 1)[1]
print("Organizer verification unchanged.")
changed = set(subprocess.check_output(["git", "diff", "--name-only"], text=True).splitlines())
changed.update(subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard"], text=True).splitlines())
allowed = {"CHANGELOG.md", "roadmap/tasks/E8-T12.md", "roadmap/STATUS.md", "roadmap/MESSAGES.md"}
assert all(path in allowed or path.startswith("roadmap/evidence/E8-T12/") for path in changed), sorted(changed)
print("Session scope: only E8-T12 evidence/report, changelog, generated status and append-only messages.")
print("No product, Java test, API or catalog changes in this session.")
