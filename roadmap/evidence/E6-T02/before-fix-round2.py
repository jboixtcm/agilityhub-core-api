"""E6-T02 round 2 before-fix run: copies the working tree to target/before-fix-r2 (the working tree is never touched), puts
back the committed (HEAD) version of the production files that round 2 changed, removes the two new production classes,
keeps every test of the working tree, and runs the tests that pin round 2's fixes. Each must fail there."""
import re
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
TREE = ROOT / "target/before-fix-r2"
MAIN = "src/main/java/com/agilityhub/core/"
RESTORED = [  # read-only `git show HEAD:<path>`
    MAIN + "shared/api/IdempotencyFilter.java",            # item 1: the replay moved behind the handler's authorization
    MAIN + "shared/api/ApiExceptionHandler.java",          # item 1: writes the stored response
    MAIN + "shared/application/SharedConfiguration.java",  # item 1: registers the aspect
    MAIN + "clubs/bookings/application/AttendanceSheetQuery.java",  # item 2: dogs without a level
]
REMOVED = [MAIN + "shared/api/IdempotentReplay.java", MAIN + "shared/api/IdempotentReplayAspect.java"]
TESTS = "AttendanceIT#T_10_22_aStoredAnswer*+T_10_33_dogsWithoutALevel*,IdempotentReplayContractIT"

shutil.rmtree(TREE, ignore_errors=True)
TREE.mkdir(parents=True)
for name in ("src", ".mvn", "docs", "seeds"):
    shutil.copytree(ROOT / name, TREE / name)
for name in ("pom.xml", "mvnw"):
    shutil.copy2(ROOT / name, TREE / name)
for path in RESTORED:
    committed = subprocess.run(["git", "show", "HEAD:" + path], cwd=ROOT, check=True, capture_output=True).stdout
    (TREE / path).write_bytes(committed)
    print(f"restored HEAD in the copy: {path}")
for path in REMOVED:
    (TREE / path).unlink()
    print(f"removed in the copy: {path}")
command = ["./mvnw", "-q", "verify", "-Dtest=NONE", "-Dsurefire.failIfNoSpecifiedTests=false", "-Dit.test=" + TESTS, "-Djacoco.skip=true"]
print("$ (cd target/before-fix-r2 && " + " ".join(command) + ")", flush=True)
result = subprocess.run(command, cwd=TREE, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
print(f"exit {result.returncode}")
for line in result.stdout.splitlines():
    if re.search(r"Tests run:|<<< (FAILURE|ERROR)|^\[ERROR\]   [A-Za-z]|E62 handler methods", line) or re.match(r"^(expected|but was|Expecting|org\.opentest4j|java\.lang\.AssertionError)", line.strip()):
        print(line[:400].rstrip())
