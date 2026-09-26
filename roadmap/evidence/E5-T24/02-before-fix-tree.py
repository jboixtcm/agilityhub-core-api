import pathlib
import shutil
import subprocess
import sys

# E5-T24: the unfixed trees for the before-fix runs, built under target/ without touching the working tree.
# Tree A (target/before-fix): every file under src/main, seeds and docs/openapi that differs from HEAD (e9a58c1, main
# before E5-T24) gets its HEAD content back, and the files E5-T24 adds there are removed. The tests are the current ones,
# except the three classes that call an API this task adds and so cannot compile on the unfixed code: AttachmentServiceTest
# and DemoSeedsIT get their HEAD content back, and ListContractValidationTest (new) is removed.
# Tree B (target/before-fix-b, with --without-own-plan-in-add-dog): the fixed tree with only step 6's add-dog check undone
# (POST /me/dogs/signup falls back to the member's plan even when it is gone), so that its 400 REQUIRED guard is shown
# failing on its own, after GET /signup's fix. Run from the repository root.
variant_b = "--without-own-plan-in-add-dog" in sys.argv
BASE = "HEAD"
SCOPE = ["src/main", "seeds", "docs/openapi"]
NOT_COMPILABLE = ["src/test/java/com/agilityhub/core/clubs/followup/application/AttachmentServiceTest.java",
                  "src/test/java/com/agilityhub/core/clubs/census/application/DemoSeedsIT.java",
                  "src/test/java/com/agilityhub/core/configuration/ListContractValidationTest.java"]
root = pathlib.Path.cwd()
dest = root / ("target/before-fix-b" if variant_b else "target/before-fix")
if dest.exists():
    shutil.rmtree(dest)
dest.mkdir(parents=True)
for name in ("src", "docs", "seeds", ".mvn"):
    shutil.copytree(root / name, dest / name)
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
    service = dest / "src/main/java/com/agilityhub/core/clubs/census/application/SignupService.java"
    fixed = "String planId=requested==null?ownPlan(member):requested;"
    text = service.read_text()
    if text.count(fixed) != 1:
        sys.exit("step 6's add-dog check not found exactly once")
    service.write_text(text.replace(fixed, "String planId=requested==null?member.planId:requested;"))
    print("undone in tree B: SignupService.addDog falls back to member.planId (step 6)")
else:
    for path in git("diff", "--name-only", BASE, "--", *SCOPE):
        restore(path)
    for path in git("ls-files", "--others", "--exclude-standard", "--", *SCOPE):
        restore(path)
    for path in NOT_COMPILABLE:
        restore(path)
print("tree ready:", dest.relative_to(root))
