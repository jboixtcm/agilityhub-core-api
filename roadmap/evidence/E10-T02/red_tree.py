#!/usr/bin/env python3
"""Round 2 red runs: build target/e10t02-red = the round-1 product code (HEAD) plus this round's tests.

Copies the working tree's build inputs, then restores every product file this round changes from `git show HEAD:<file>`
(read-only git) and removes the files it adds. Tests stay at their round-2 version, except StripePaymentProviderTest, whose
only change follows the new `Connection(accountId, …)` record and would not compile against the round-1 code.
With --keep-audit-enum, AuditAction.java keeps its round-2 values (no behaviour: the mutations still write CLUB_UPDATED), so
the tests annotated @AuditCovers compile and their assertions run; attempt 46 shows the compile-level red without it.
"""
import pathlib, shutil, subprocess, sys

root = pathlib.Path(__file__).resolve().parents[3]
red = root / "target" / "e10t02-red"
if red.exists():
    shutil.rmtree(red)
red.mkdir(parents=True)
for name in ("src", "seeds", "docs", ".mvn"):
    shutil.copytree(root / name, red / name)
for name in ("pom.xml", "mvnw"):
    shutil.copy2(root / name, red / name)

def head(path):
    return subprocess.run(["git", "-C", str(root), "show", "HEAD:" + path], check=True, capture_output=True).stdout

changed = subprocess.run(["git", "-C", str(root), "diff", "--name-only", "HEAD", "--", "src/main", "docs/openapi"],
                         check=True, capture_output=True, text=True).stdout.split()
if "--keep-audit-enum" in sys.argv[1:]:
    changed.remove("src/main/java/com/agilityhub/core/platform/application/audit/AuditAction.java")
restored = changed + ["src/test/java/com/agilityhub/core/payments/application/stripe/StripePaymentProviderTest.java"]
for path in restored:
    (red / path).write_bytes(head(path))
added = subprocess.run(["git", "-C", str(root), "ls-files", "--others", "--exclude-standard", "--", "src/main"],
                       check=True, capture_output=True, text=True).stdout.split()
for path in added:
    (red / path).unlink()
# The unit test of an added class has nothing to test in the round-1 code.
(red / "src/test/java/com/agilityhub/core/platform/application/ConsoleTransactionsTest.java").unlink(missing_ok=True)
print("restored from HEAD:", *restored, sep="\n  ")
print("removed (added by round 2):", *added, sep="\n  ")
sys.exit(0)
