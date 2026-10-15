"""E111 point 3 in isolation: re-add round 5's refresh of the interventions before absorption to the fixed code, run the
round-6 integration tests, then restore the fix byte for byte.

Usage: python3 roadmap/evidence/E8-T12/counterfactual-round6-point3.py <log>
"""
import hashlib
import pathlib
import subprocess
import sys

FILE = pathlib.Path("src/main/java/com/agilityhub/core/payments/application/PaymentRefunds.java")
ANCHOR = "    private void absorb(String intent) {\n"
ROUND5_REFRESH = """        for (var payment : upfront.forIntent(intent)) {
            if (upfront.compensation(payment.id()).filter(obligation -> obligation.interventionAt() != null).isPresent()) {
                upfront.lock(payment.id());
                compensateRemaining(payment);
            }
        }
"""
log = pathlib.Path(sys.argv[1])
fixed = FILE.read_bytes()
source = fixed.decode()
assert source.count(ANCHOR) == 1
try:
    FILE.write_text(source.replace(ANCHOR, ANCHOR + ROUND5_REFRESH))
    with log.open("w") as out:
        code = subprocess.run(["./mvnw", "-q", "verify", "-Dtest=NoUnitTest", "-Dit.test=CapturedPaymentInvariantsIT#T_12_17_e8t12_round*",
                               "-Djacoco.skip=true", "-Dsurefire.failIfNoSpecifiedTests=false"], stdout=out, stderr=subprocess.STDOUT).returncode
    print(f"maven exit with round 5's pre-absorption refresh re-added: {code}")
finally:
    FILE.write_bytes(fixed)
    print(f"restored {FILE}: identical to the fix: {hashlib.sha256(FILE.read_bytes()).digest() == hashlib.sha256(fixed).digest()}")
