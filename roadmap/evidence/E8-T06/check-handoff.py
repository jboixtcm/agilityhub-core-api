#!/usr/bin/env python3
"""Read-only scope, whitespace and evidence checks for the E8-T06 handoff."""
from pathlib import Path
import re
import subprocess


def git(*args):
    return subprocess.check_output(["git", *args], text=True)


subprocess.run(["git", "diff", "--check"], check=True)
print("git diff --check: exit 0")
changed = set(git("diff", "--name-only").splitlines())
untracked = set(git("ls-files", "--others", "--exclude-standard").splitlines())
allowed = {"CHANGELOG.md", "roadmap/STATUS.md", "roadmap/MESSAGES.md", "roadmap/tasks/E8-T06.md"}
assert all(p in allowed or p.startswith("roadmap/evidence/E8-T06/") for p in changed | untracked)
print("Working-tree scope: E8-T06 report/evidence, changelog, messages and generated status only")
task = Path("roadmap/tasks/E8-T06.md")
baseline = git("show", "HEAD:roadmap/tasks/E8-T06.md")
assert task.read_text().split("## Organizer verification", 1)[1] == baseline.split("## Organizer verification", 1)[1]
assert task.stat().st_size < 120_000
print("Organizer verification unchanged; task size below 120000 bytes")
for name in untracked:
    p = Path(name)
    if p.suffix in (".py", ".log"):
        assert all(line == line.rstrip() for line in p.read_text().splitlines()), f"Trailing whitespace: {name}"
print("New evidence/helper files: no trailing whitespace")
patterns = {
    "JWT": r"eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+",
    "Stripe secret": r"(?:sk_(?:test|live)_|whsec_)[A-Za-z0-9]{16,}",
    "IBAN": r"\bES\d{22}\b",
    "private key": r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----",
}
for name in untracked:
    if name.endswith(".log"):
        content = Path(name).read_text()
        for kind, pattern in patterns.items():
            assert not re.search(pattern, content), f"{kind} pattern found in {name}; value withheld"
print("New evidence logs: no complete JWT, Stripe secret, Spanish IBAN or private-key patterns")
