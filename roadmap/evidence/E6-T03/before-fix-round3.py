"""E6-T03 round 3 before-fix run. Copies the working tree to target/before-fix-e6t03-r3 (the working tree is never touched),
puts back the committed (HEAD, round 2) version of every production file under src/main that round 3 changed, and runs the
round-3 tests there. Each of them must fail on the round-2 code.

One production file keeps its round-3 version in the copy: NotificationFactsPort.java. Round 3 adds to it one default method,
`deliverable(...)`, that answers `true`; the round-3 tests call it (FollowupNotificationsTest) or override it
(NotificationDispatcherIT), so they would not compile without it. With HEAD's dispatcher nobody calls it and HEAD's
FollowupNotificationFacts does not override it: the copy behaves as round 2 did. No test file is changed in the copy.
Read-only git only (`git diff --name-only`, `git ls-files`, `git show HEAD:<path>`).
Run from the repository root: python3 roadmap/evidence/E6-T03/before-fix-round3.py
"""
import re
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
TREE = ROOT / "target/before-fix-e6t03-r3"
KEPT = {"src/main/java/com/agilityhub/core/clubs/messaging/application/ports/NotificationFactsPort.java"}
UNIT = "FollowupNotificationsTest,FollowupProjectionTest"
IT = "FollowupIT,NotificationDispatcherIT"


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
for path in changed:
    if path in KEPT:
        print(f"kept round 3 in the copy (default no-op method only): {path}")
        continue
    (TREE / path).write_bytes(git("show", "HEAD:" + path))
    print(f"restored HEAD in the copy: {path}")
command = ["./mvnw", "-q", "verify", "-Dtest=" + UNIT, "-Dsurefire.failIfNoSpecifiedTests=false", "-Dit.test=" + IT,
           "-Dmaven.test.failure.ignore=true", "-Djacoco.skip=true"]
print("$ (cd target/before-fix-e6t03-r3 && " + " ".join(command) + ")", flush=True)
result = subprocess.run(command, cwd=TREE, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
print(f"exit {result.returncode}")
for line in result.stdout.splitlines():
    if re.search(r"Tests run:|<<< (FAILURE|ERROR)|^\[ERROR\]   [A-Za-z]", line) or re.match(r"^(expected|but was|Expecting|org\.opentest4j|java\.lang\.AssertionError)", line.strip()):
        print(line[:400].rstrip())
print("--- per test (surefire + failsafe XML of the copy), with the first line of each failure")
for folder in ("surefire-reports", "failsafe-reports"):
    for report in sorted((TREE / "target" / folder).glob("TEST-*.xml")):
        xml = report.read_text()
        for case in re.finditer(r'<testcase name="([^"]+)" classname="([^"]+)"[^>]*?(/>|>(.*?)</testcase>)', xml, re.S):
            body = case.group(4) or ""
            status = "FAILED" if "<failure" in body else "ERROR" if "<error" in body else "ok"
            print(f"{status:6} {case.group(2).split('.')[-1]}.{case.group(1)}")
            failure = re.search(r'<(failure|error) message="([^"]*)"', body)
            if failure:
                message = failure.group(2).replace("&#10;", " ").replace("&quot;", '"').replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
                print("       " + re.sub(r"\s+", " ", message)[:300])
