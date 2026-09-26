import pathlib
import shutil
import subprocess
import sys

# E5-T25: the trees for the before-fix runs, built under target/ without touching the working tree. Run from the repository root.
# Tree A (target/before-fix): every file under src/main and docs/openapi that differs from HEAD (1623eba, main before E5-T25)
# gets its HEAD content back, and the files E5-T25 adds there are removed. The tests are the current ones, except
# BookingRulesTest, which calls the new CancellationPolicy.inTimeUntil and so cannot compile on the unfixed code: it gets its
# HEAD content back.
# Tree B (target/before-fix-b, with --booking-not-cancellable-409): the fixed tree with only ErrorCode.BOOKING_NOT_CANCELLABLE
# moved to 409, so that step 7's contract test is shown failing once the status (and the snapshot generated from it) changes.
variant_b = "--booking-not-cancellable-409" in sys.argv
BASE = "HEAD"
SCOPE = ["src/main", "docs/openapi"]
NOT_COMPILABLE = ["src/test/java/com/agilityhub/core/clubs/bookings/domain/BookingRulesTest.java"]
root = pathlib.Path.cwd()
dest = root / ("target/before-fix-b" if variant_b else "target/before-fix")
if dest.exists():
    shutil.rmtree(dest)
dest.mkdir(parents=True)
for name in ("src", "docs", "seeds", ".mvn", "bin"):
    shutil.copytree(root / name, dest / name, ignore=shutil.ignore_patterns("__pycache__"))
for name in ("pom.xml", "mvnw"):
    shutil.copy2(root / name, dest / name)


def git(*args):
    return subprocess.run(["git", *args], capture_output=True, text=True, check=True).stdout.split()


def restore(path):
    blob = subprocess.run(["git", "show", BASE + ":" + path], capture_output=True)
    if blob.returncode != 0:
        (dest / path).unlink(missing_ok=True)
        print("removed (added after " + BASE + "):", path)
        return
    (dest / path).write_bytes(blob.stdout)
    print("back to " + BASE + ":", path)


if variant_b:
    codes = dest / "src/main/java/com/agilityhub/core/shared/domain/ErrorCode.java"
    fixed = "BOOKING_NOT_CANCELLABLE(422),"
    text = codes.read_text()
    if text.count(fixed) != 1:
        sys.exit("BOOKING_NOT_CANCELLABLE(422) not found exactly once")
    codes.write_text(text.replace(fixed, "BOOKING_NOT_CANCELLABLE(409),"))
    print("changed in tree B: ErrorCode.BOOKING_NOT_CANCELLABLE answers 409 (step 7)")
else:
    for path in git("diff", "--name-only", BASE, "--", *SCOPE):
        restore(path)
    for path in git("ls-files", "--others", "--exclude-standard", "--", *SCOPE):
        restore(path)
    for path in NOT_COMPILABLE:
        restore(path)
print("tree ready:", dest.relative_to(root))
