"""E6-T03 round 4 before-fix run. Copies the working tree to target/before-fix-e6t03-r4 (the working tree is never touched),
puts the round-3 code back in the copy, and runs the round-4 tests there. Each new test must fail on the round-3 code.

- Review #1 (observations 409, never 500): IdempotencyFilter, FollowupController and FollowupTransactions get their committed
  (HEAD, round 3) version.
- Review #2 (the projection deduplicates by eventId): the round-4 tests call `memberNote(eventId, event)` and build
  `FollowupItem` with `lastEventId`, so FollowupItem, FollowupItemRepository and FollowupProjection keep their round-4
  signatures in the copy. The two lines of the fix are reverted in the copy's FollowupProjection instead: the early return
  on the stored `lastEventId`, and the millisecond truncation of the event's instant. That is round 3's logic with round 4's
  signatures (the stored `lastEventId` is written but never read).
No test file is changed in the copy. Read-only git only (`git diff --name-only`, `git ls-files`, `git show HEAD:<path>`).
Run from the repository root: python3 roadmap/evidence/E6-T03/before-fix-round4.py
"""
import re
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
TREE = ROOT / "target/before-fix-e6t03-r4"
BASE = "src/main/java/com/agilityhub/core/"
RESTORED = {BASE + "shared/api/IdempotencyFilter.java", BASE + "clubs/followup/api/FollowupController.java",
            BASE + "clubs/followup/application/FollowupTransactions.java"}
SIGNATURES = {BASE + "clubs/followup/persistence/FollowupItem.java", BASE + "clubs/followup/persistence/FollowupItemRepository.java"}
PROJECTION = BASE + "clubs/followup/application/FollowupProjection.java"
REVERTS = [
    ("            if (stored != null && eventId != null && eventId.equals(stored.lastEventId())) { return; } // the same event again (S10 §7)\n", ""),
    ("            Instant occurredAt = event.occurredAt().truncatedTo(ChronoUnit.MILLIS);\n", "            Instant occurredAt = event.occurredAt();\n"),
]
UNIT = "FollowupProjectionTest"
IT = "FollowupIT"


def git(*args):
    return subprocess.run(["git", *args], cwd=ROOT, check=True, capture_output=True).stdout


shutil.rmtree(TREE, ignore_errors=True)
TREE.mkdir(parents=True)
for name in ("src", ".mvn", "docs", "seeds"):
    shutil.copytree(ROOT / name, TREE / name)
for name in ("pom.xml", "mvnw"):
    shutil.copy2(ROOT / name, TREE / name)
changed = set(git("diff", "--name-only", "HEAD", "--", "src/main").decode().split())
added = git("ls-files", "--others", "--exclude-standard", "--", "src/main").decode().split()
if added:
    raise SystemExit("unexpected new production files: " + ", ".join(added))
if changed != RESTORED | SIGNATURES | {PROJECTION}:
    raise SystemExit("unexpected production changes: " + ", ".join(sorted(changed ^ (RESTORED | SIGNATURES | {PROJECTION}))))
for path in sorted(RESTORED):
    (TREE / path).write_bytes(git("show", "HEAD:" + path))
    print(f"restored HEAD in the copy: {path}")
for path in sorted(SIGNATURES):
    print(f"kept round 4 in the copy (the stored field / parameter only): {path}")
source = (TREE / PROJECTION).read_text(encoding="utf-8")
for before, after in REVERTS:
    if source.count(before) != 1:
        raise SystemExit("fix line not found once: " + before.strip())
    source = source.replace(before, after)
    print(f"reverted in the copy's FollowupProjection: {before.strip()}")
(TREE / PROJECTION).write_text(source, encoding="utf-8")
command = ["./mvnw", "-q", "verify", "-Dtest=" + UNIT, "-Dsurefire.failIfNoSpecifiedTests=false", "-Dit.test=" + IT,
           "-Dmaven.test.failure.ignore=true", "-Djacoco.skip=true"]
print("$ (cd target/before-fix-e6t03-r4 && " + " ".join(command) + ")", flush=True)
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
                print("       " + re.sub(r"\s+", " ", message)[:400])
