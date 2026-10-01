#!/usr/bin/env python3
"""E8-T03 counterfactual: undo each fix of this task in the working tree, run the tests that cover them, restore every file.

Each swap must match exactly once; the files are restored from a byte copy whatever happens."""
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
LOG = ROOT / "roadmap/evidence/E8-T03/01-before-the-fix.log"
J = "src/main/java/com/agilityhub/core/"
SWAPS = [
    # Step 6: no new mandate when the account or the method changes.
    (J + "clubs/census/application/MemberService.java",
     "if (!sameMandate && !account.isEmpty() && member.memberNumber != null) {", "if (false) {"),
    # Step 9: SEPA_XML without creditor data is not PROVIDER_DISABLED in the simulation.
    (J + "payments/application/BillingSimulationService.java",
     "boolean sepaUnusable = context.settings().enabledMethods().contains(PaymentMethodType.SEPA_DD)", "boolean sepaUnusable = false && context.settings().enabledMethods().contains(PaymentMethodType.SEPA_DD)"),
    # R-12-19 guard: a non-positive manual receipt rides the remittance.
    (J + "payments/persistence/BillingDocuments.java", '.and("remittanceId").is(null).and("total.amountMinor").gt(0))', '.and("remittanceId").is(null))'),
    # R-12-12 FRST: a debit of a remittance sent to the bank does not count as collected.
    (J + "payments/application/sepa/SepaRemittanceWriter.java", "|| sent.contains(attempt.remittanceId()))", ")"),
    # R-12-11: the stored file of a rolled-back run is not deleted.
    (J + "payments/application/sepa/SepaRemittanceWriter.java", "if (TransactionSynchronizationManager.isSynchronizationActive()) {", "if (false) {"),
    # R-12-12: a mandate that no longer matches the member's is not refused.
    (J + "payments/application/sepa/SepaRemittanceWriter.java", "|| !Objects.equals(mandate, account.mandateRef())", ""),
]
COMMANDS = [["./mvnw", "-q", "test", "-Dtest=SepaRemittanceWriterTest", "-Dsurefire.failIfNoSpecifiedTests=false", "-Djacoco.skip=true"],
            ["./mvnw", "-q", "verify", "-Dtest=NoSuchUnitTest", "-Dsurefire.failIfNoSpecifiedTests=false", "-Dit.test=RemittancesIT",
             "-Dfailsafe.failIfNoSpecifiedTests=false", "-Djacoco.skip=true"]]


def main():
    backup = ROOT / "target/cf-backup"
    files = sorted({path for path, _, _ in SWAPS})
    if backup.exists():
        shutil.rmtree(backup)
    for path in files:
        (backup / path).parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / path, backup / path)
    try:
        for path, old, new in SWAPS:
            text = (ROOT / path).read_text()
            if text.count(old) != 1:
                raise SystemExit(f"swap does not match once in {path}: {old}")
            (ROOT / path).write_text(text.replace(old, new))
        with LOG.open("w") as log:
            log.write("# E8-T03 counterfactual: the six fixes undone in the working tree (target/e8t03-counterfactual.py), then restored.\n")
            for path, old, new in SWAPS:
                log.write(f"#   {path}: «{old}» → «{new}»\n")
            for command in COMMANDS:
                log.write("$ " + " ".join(command) + "\n")
                log.flush()
                result = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
                log.write(f"exit {result.returncode}\n")
                log.flush()
                print(f"counterfactual {command[2]} exit {result.returncode}")
    finally:
        for path in files:
            shutil.copy2(backup / path, ROOT / path)
        for path in files:
            if (ROOT / path).read_bytes() != (backup / path).read_bytes():
                raise SystemExit(f"restore failed: {path}")
        print("restored " + ", ".join(files))


if __name__ == "__main__":
    main()
