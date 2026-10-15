"""E111 counterfactual: run the final round-6 tests against HEAD's (round 5) production code, then restore the fix.

Usage: python3 roadmap/evidence/E8-T12/counterfactual-round6.py <log> [unit-test-selector]
(a selector that matches no unit class, e.g. NoUnitTest, lets the integration class run although the unit class fails)
Only the three production files change; the tests stay as they are in the working tree. The fixed sources are copied to
target/round6-fixed/ first and restored byte for byte afterwards, whatever the Maven result.
"""
import hashlib
import pathlib
import shutil
import subprocess
import sys

FILES = [
    "src/main/java/com/agilityhub/core/payments/application/PaymentRefunds.java",
    "src/main/java/com/agilityhub/core/payments/domain/PaymentIntentLedger.java",
    "src/main/java/com/agilityhub/core/payments/persistence/UpfrontPaymentRepository.java",
]
log = pathlib.Path(sys.argv[1])
unit = sys.argv[2] if len(sys.argv) > 2 else "PaymentIntentLedgerTest"
backup = pathlib.Path("target/round6-fixed")
backup.mkdir(parents=True, exist_ok=True)
digest = {}
for f in FILES:
    digest[f] = hashlib.sha256(pathlib.Path(f).read_bytes()).hexdigest()
    shutil.copy2(f, backup / pathlib.Path(f).name)
try:
    for f in FILES:
        pathlib.Path(f).write_bytes(subprocess.run(["git", "show", "HEAD:" + f], check=True, capture_output=True).stdout)
    with log.open("w") as out:
        code = subprocess.run(["./mvnw", "-q", "verify", "-Dtest=" + unit, "-Dit.test=CapturedPaymentInvariantsIT",
                               "-Djacoco.skip=true", "-Dsurefire.failIfNoSpecifiedTests=false"], stdout=out, stderr=subprocess.STDOUT).returncode
    print(f"maven exit on HEAD production code: {code}")
finally:
    for f in FILES:
        shutil.copy2(backup / pathlib.Path(f).name, f)
        same = hashlib.sha256(pathlib.Path(f).read_bytes()).hexdigest() == digest[f]
        print(f"restored {f}: identical to the fix: {same}")
