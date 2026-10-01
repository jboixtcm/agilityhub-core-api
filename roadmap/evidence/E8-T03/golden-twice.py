#!/usr/bin/env python3
"""E8-T03 evidence (T-12-11): the golden-file test run twice gives the same bytes, and the schema in use accepts the file.

Run 1 is the full `./mvnw -q clean verify` (it leaves target/sepa/remesa-2026-09.actual.xml); run 2 is RemittancesIT's T-12-11
alone. Both outputs are compared with each other and with src/test/resources/sepa/remesa-2026-09.golden.xml (`cmp`), then
`xmllint --schema` validates the golden file and run 2's file against src/main/resources/sepa/pain.008.001.02.xsd."""
import hashlib
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
ACTUAL, GOLDEN, RUN1 = ROOT / "target/sepa/remesa-2026-09.actual.xml", ROOT / "src/test/resources/sepa/remesa-2026-09.golden.xml", ROOT / "target/sepa/run1.xml"
XSD = "src/main/resources/sepa/pain.008.001.02.xsd"


def sh(command, label=None):
    print("$ " + (label or " ".join(command)), flush=True)
    result = subprocess.run(command, cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if result.stdout.strip():
        print(result.stdout.strip(), flush=True)
    print(f"exit {result.returncode}", flush=True)
    return result.returncode


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest() + "  " + str(path.relative_to(ROOT))


codes = []
print("# run 1: the full clean verify (06-verify.log) left target/sepa/remesa-2026-09.actual.xml", flush=True)
codes.append(sh(["cmp", str(ACTUAL.relative_to(ROOT)), str(GOLDEN.relative_to(ROOT))]))
print(digest(ACTUAL) + "\n" + digest(GOLDEN), flush=True)
shutil.copyfile(ACTUAL, RUN1)
print("# run 2: RemittancesIT's T-12-11 alone", flush=True)
maven = ["./mvnw", "-q", "verify", "-Dtest=NoSuchUnitTest", "-Dsurefire.failIfNoSpecifiedTests=false", "-Dit.test=RemittancesIT#T_12_11*",
         "-Dfailsafe.failIfNoSpecifiedTests=false", "-Djacoco.skip=true"]
print("$ " + " ".join(maven), flush=True)
result = subprocess.run(maven, cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
report = (ROOT / "target/failsafe-reports/TEST-com.agilityhub.core.payments.api.RemittancesIT.xml").read_text()
print(report[report.index("<testsuite"):report.index(">", report.index("<testsuite")) + 1][:400], flush=True)
print(f"exit {result.returncode}", flush=True)
codes.append(result.returncode)
codes.append(sh(["cmp", str(RUN1.relative_to(ROOT)), str(ACTUAL.relative_to(ROOT))]))
codes.append(sh(["cmp", str(ACTUAL.relative_to(ROOT)), str(GOLDEN.relative_to(ROOT))]))
print(digest(ACTUAL), flush=True)
print("# the schema in use (xmllint)", flush=True)
codes.append(sh(["xmllint", "--noout", "--schema", XSD, str(GOLDEN.relative_to(ROOT))]))
codes.append(sh(["xmllint", "--noout", "--schema", XSD, str(ACTUAL.relative_to(ROOT))]))
sys.exit(1 if any(codes) else 0)
