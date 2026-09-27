"""E6-T02 round 2 (review #5): the whitespace check over the whole change, new files included. `git diff --check` for the
tracked files, then `git diff --no-index --check /dev/null <file>` for every untracked one (read-only git). With --no-index
git exits 1 for "the file differs from /dev/null" and 2 when --check finds a problem, so a clean new file is exit 1 and no
output."""
import subprocess

tracked = subprocess.run(["git", "diff", "--check"], capture_output=True, text=True)
print(f"$ git diff --check -> exit {tracked.returncode}")
print(tracked.stdout.rstrip() or "  (no output)")
untracked = subprocess.run(["git", "ls-files", "--others", "--exclude-standard"], capture_output=True, text=True, check=True).stdout.split()
problems = 0
for path in untracked:
    result = subprocess.run(["git", "diff", "--no-index", "--check", "/dev/null", path], capture_output=True, text=True)
    clean = result.returncode == 1 and not result.stdout.strip()
    problems += 0 if clean else 1
    print(f"$ git diff --no-index --check /dev/null {path} -> exit {result.returncode} {'clean' if clean else 'PROBLEM'}")
    if not clean:
        print(result.stdout.rstrip()[:2000])
print(f"new files: {len(untracked)}, with whitespace problems: {problems}")
