import pathlib
import shutil
import subprocess

# E5-T22: the unfixed tree for the before-fix run, built in target/before-fix without touching the working tree.
# Every file under src/main and docs/openapi that differs from 799c21d (main before E5-T22) gets its 799c21d content back
# (a file added since then is removed); the tests are the current ones. Run from the repository root.
BASE = "799c21d"
root = pathlib.Path.cwd()
dest = root / "target/before-fix"
if dest.exists():
    shutil.rmtree(dest)
dest.mkdir(parents=True)
for name in ("src", "docs", "seeds", ".mvn"):
    shutil.copytree(root / name, dest / name)
for name in ("pom.xml", "mvnw"):
    shutil.copy2(root / name, dest / name)
changed = subprocess.run(["git", "diff", "--name-only", BASE, "--", "src/main", "docs/openapi"],
                         capture_output=True, text=True, check=True).stdout.split()
for path in changed:
    blob = subprocess.run(["git", "show", BASE + ":" + path], capture_output=True)
    if blob.returncode != 0:
        (dest / path).unlink(missing_ok=True)
        print("removed (added after " + BASE + "):", path)
        continue
    (dest / path).write_bytes(blob.stdout)
    print("back to " + BASE + ":", path)
# The one test method that calls a method the fix adds (ActivityProjection.waitlistRanks(Collection)) cannot compile on
# the unfixed code; the IT R_07_08_theMemberViewReadsTheWaitingEntriesOnceForAllItsActivities covers the same step there.
unit = dest / "src/test/java/com/agilityhub/core/clubs/activities/application/ActivityApiServiceTest.java"
text = unit.read_text()
start = text.index("    static com.agilityhub.core.clubs.activities.persistence.ActivityRegistration waiting(")
end = text.rindex("}")
unit.write_text(text[:start] + text[end:])
print("left out: ActivityApiServiceTest#R_07_08_theRanksOfSeveralActivitiesComeFromOneReadOfTheWaitingEntries (needs the fix's method)")
