import pathlib
import shutil
import subprocess
import sys

# E5-T26: the unfixed trees for the before-fix runs, built under target/ without touching the working tree. Run from the
# repository root.
# Tree A (target/before-fix): every file under src/main that differs from HEAD (c70adf9, main before E5-T26) gets its HEAD
# content back. The tests are the current ones, except AttachmentServiceTest, which calls the `Download` this task adds and
# so cannot compile on the unfixed code: it gets its HEAD content back (trees B and C run its new tests).
# Tree B (target/before-fix-b, --without-tenant): the fixed tree with only step 4 undone: the signed routes' handler runs
# without the grant's club as the tenant.
# Tree C (target/before-fix-c, --without-member-account): the fixed tree with only step 1's grant change undone: a MEMBER's
# signup upload URL stores no account, as before.
VARIANTS = {
    "--without-tenant": ("target/before-fix-b",
                         "src/main/java/com/agilityhub/core/clubs/followup/application/AttachmentService.java",
                         "try (var tenant = TenantContext.open(grant.clubId())) { return handler.handle(grant); }",
                         "return handler.handle(grant);",
                         "step 4: the signed routes' handler runs without the grant's club as the tenant"),
    "--without-member-account": ("target/before-fix-c",
                                 "src/main/java/com/agilityhub/core/clubs/followup/application/AttachmentService.java",
                                 "user==null?null:user.accountId()",
                                 "null",
                                 "step 1: a MEMBER's signup upload URL stores no account"),
}
BASE = "HEAD"
SCOPE = ["src/main"]
NOT_COMPILABLE = ["src/test/java/com/agilityhub/core/clubs/followup/application/AttachmentServiceTest.java"]
variant = next((argument for argument in sys.argv[1:] if argument in VARIANTS), None)
root = pathlib.Path.cwd()
dest = root / (VARIANTS[variant][0] if variant else "target/before-fix")
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


if variant:
    _, path, fixed, unfixed, label = VARIANTS[variant]
    source = dest / path
    text = source.read_text()
    if text.count(fixed) != 1:
        sys.exit("the fix of " + label + " was not found exactly once")
    source.write_text(text.replace(fixed, unfixed))
    print("undone in " + dest.relative_to(root).as_posix() + ": " + label)
else:
    for path in git("diff", "--name-only", BASE, "--", *SCOPE):
        restore(path)
    for path in git("ls-files", "--others", "--exclude-standard", "--", *SCOPE):
        restore(path)
    for path in NOT_COMPILABLE:
        restore(path)
print("tree ready:", dest.relative_to(root))
