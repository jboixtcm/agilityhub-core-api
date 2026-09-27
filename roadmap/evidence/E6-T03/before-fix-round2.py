"""E6-T03 round 2 before-fix run. Copies the working tree to target/before-fix-e6t03-r2 (the working tree is never touched),
puts back the committed (HEAD) version of every production file under src/main that round 2 changed, and runs the tests that
pin the five review points there. Each of those tests must fail on the round-1 code.

Tests that cannot compile against the round-1 code get their HEAD version in the copy (their round-2 cases are covered by
the ITs that do run): FollowupProjectionTest and FollowupServiceTest (the new `FollowupItem.authorGender` and the new
`note(...)` signature), ListQueryTest (`withMaxSize`) and ListContractValidationTest (`maxSize`). In FollowupContractAccessTest,
E6ContractIT and E6PersistenceIT only their `FollowupItem` fixtures are rewritten to the round-1 constructor (without the
author's gender); their assertions are the round-2 ones. Read-only git only (`git diff --name-only`, `git show HEAD:<path>`).
Run from the repository root: python3 roadmap/evidence/E6-T03/before-fix-round2.py
"""
import re
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
TREE = ROOT / "target/before-fix-e6t03-r2"
TEST = "src/test/java/com/agilityhub/core/"
RESTORED_TESTS = [
    TEST + "clubs/followup/application/FollowupProjectionTest.java",
    TEST + "clubs/followup/application/FollowupServiceTest.java",
    TEST + "shared/application/lists/ListQueryTest.java",
    TEST + "configuration/ListContractValidationTest.java",
]
ROUND1_FIXTURES = {  # path -> (round-2 text, round-1 text): the FollowupItem constructor without authorGender
    TEST + "clubs/followup/application/FollowupContractAccessTest.java": [('"Estel", null, "Practiqueu"', '"Estel", "Practiqueu"'),
                                                                         ('"Estel", null, "Pujar"', '"Estel", "Pujar"')],
    TEST + "configuration/E6ContractIT.java": [('"Estel", null, "Practiqueu el balancí"', '"Estel", "Practiqueu el balancí"')],
    TEST + "configuration/E6PersistenceIT.java": [('"Estel", null, "Practiqueu el balancí"', '"Estel", "Practiqueu el balancí"')],
}
UNIT = "FollowupContractAccessTest,FollowupNotificationsTest"
IT = "FollowupIT,E6ContractIT#T_10_21_snapshotPublishesEveryOperationWithTypedResponsesAndListMetadata"


def git(*args):
    return subprocess.run(["git", *args], cwd=ROOT, check=True, capture_output=True).stdout


shutil.rmtree(TREE, ignore_errors=True)
TREE.mkdir(parents=True)
for name in ("src", ".mvn", "docs", "seeds"):
    shutil.copytree(ROOT / name, TREE / name)
for name in ("pom.xml", "mvnw"):
    shutil.copy2(ROOT / name, TREE / name)
changed = git("diff", "--name-only", "HEAD", "--", "src/main").decode().split()
added = git("ls-files", "--others", "--exclude-standard", "--", "src/main").decode().split()
if added:
    raise SystemExit("unexpected new production files: " + ", ".join(added))
for path in changed + RESTORED_TESTS:
    (TREE / path).write_bytes(git("show", "HEAD:" + path))
    print(f"restored HEAD in the copy: {path}")
for path, replacements in ROUND1_FIXTURES.items():
    source = TREE / path
    text = source.read_text()
    for fixed, unfixed in replacements:
        if text.count(fixed) != 1:
            raise SystemExit(f"{path}: fixture not found exactly once: {fixed}")
        text = text.replace(fixed, unfixed)
    source.write_text(text)
    print(f"round-1 FollowupItem fixture in the copy: {path}")
command = ["./mvnw", "-q", "verify", "-Dtest=" + UNIT, "-Dsurefire.failIfNoSpecifiedTests=false", "-Dit.test=" + IT,
           "-Dmaven.test.failure.ignore=true", "-Djacoco.skip=true"]
print("$ (cd target/before-fix-e6t03-r2 && " + " ".join(command) + ")", flush=True)
result = subprocess.run(command, cwd=TREE, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
print(f"exit {result.returncode}")
for line in result.stdout.splitlines():
    if re.search(r"Tests run:|<<< (FAILURE|ERROR)|^\[ERROR\]   [A-Za-z]", line) or re.match(r"^(expected|but was|Expecting|org\.opentest4j|java\.lang\.AssertionError)", line.strip()):
        print(line[:400].rstrip())
print("--- per test (surefire + failsafe XML of the copy)")
for folder in ("surefire-reports", "failsafe-reports"):
    for report in sorted((TREE / "target" / folder).glob("TEST-*.xml")):
        xml = report.read_text()
        for case in re.finditer(r'<testcase name="([^"]+)" classname="([^"]+)"[^>]*?(/>|>(.*?)</testcase>)', xml, re.S):
            body = case.group(4) or ""
            status = "FAILED" if "<failure" in body else "ERROR" if "<error" in body else "ok"
            print(f"{status:6} {case.group(2).split('.')[-1]}.{case.group(1)}")
