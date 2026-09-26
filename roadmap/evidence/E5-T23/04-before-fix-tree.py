import pathlib
import shutil
import subprocess
import sys

# E5-T23: the unfixed trees for the before-fix runs, built under target/ without touching the working tree.
# Tree A (target/before-fix): every file under src/main and seeds that differs from HEAD (9d34d35, main before E5-T23) gets
# its HEAD content back; the tests are the current ones.
# Tree B (target/before-fix-b, with --without-removed-key-check): tree A without E5-T21's refusal of a removed key
# (SignupService.claimDocuments), which step 3's new submission IT guards. Run from the repository root.
BASE = "HEAD"
variant_b = "--without-removed-key-check" in sys.argv
root = pathlib.Path.cwd()
dest = root / ("target/before-fix-b" if variant_b else "target/before-fix")
if dest.exists():
    shutil.rmtree(dest)
dest.mkdir(parents=True)
for name in ("src", "docs", "seeds", ".mvn"):
    shutil.copytree(root / name, dest / name)
for name in ("pom.xml", "mvnw"):
    shutil.copy2(root / name, dest / name)
changed = subprocess.run(["git", "diff", "--name-only", BASE, "--", "src/main", "seeds"],
                         capture_output=True, text=True, check=True).stdout.split()
for path in changed:
    blob = subprocess.run(["git", "show", BASE + ":" + path], capture_output=True)
    if blob.returncode != 0:
        (dest / path).unlink(missing_ok=True)
        print("removed (added after " + BASE + "):", path)
        continue
    (dest / path).write_bytes(blob.stdout)
    print("back to " + BASE + ":", path)
if variant_b:
    service = dest / "src/main/java/com/agilityhub/core/clubs/census/application/SignupService.java"
    check = "if(document.stored().removed().contains(key)) throw new ApiException(ErrorCode.FILE_NOT_FOUND);"
    text = service.read_text()
    if text.count(check) != 1:
        sys.exit("E5-T21's removed-key check not found exactly once")
    service.write_text(text.replace(check, "// before-fix B: E5-T21's removed-key check disabled"))
    print("disabled in tree B: SignupService removed-key check (E5-T21)")
print("tree ready:", dest.relative_to(root))
