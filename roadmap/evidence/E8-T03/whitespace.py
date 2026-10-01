#!/usr/bin/env python3
"""E8-T03: the whitespace check covers everything this task delivers (the E8-T01 round-2 precedent).

  strip   remove trailing whitespace (and blank lines at the end) from roadmap/evidence/E8-T03/*.log; meaning unchanged
  check   `git diff --check -- <every tracked path this task changes>` and, for the files it adds (untracked until the publish
          commit), git's own rules applied to their bytes; prints the commands. Read-only git only (`git status`, `git diff --check`).
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
# Files other sessions (the organizer) changed in this working tree during the session: not E8-T03's, left untouched.
OTHERS = {"docs/DECISIONS_PENDENTS.md", "docs/INCIDENCIES_OBERTES.md", "docs/specs/S11-comunicacions.md", "docs/specs/S12-facturacio-i-pagaments.md",
          "docs/specs/S14-tauler-auditoria-exportacions-rgpd.md", "roadmap/MESSAGES.md", "roadmap/ROADMAP.md", "roadmap/tasks/E7-T07.md",
          "roadmap/tasks/E8-T02.md", "roadmap/tasks/E8-T07.md"}


def git(*args):
    return subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True)


def strip():
    for log in sorted((ROOT / "roadmap/evidence/E8-T03").glob("*.log")):
        text = log.read_text(errors="surrogateescape")
        cleaned = "\n".join(re.sub(r"[ \t\r\f\v]+$", "", line) for line in text.split("\n")).rstrip("\n")
        cleaned = cleaned + "\n" if cleaned else ""
        if cleaned != text:
            log.write_text(cleaned, errors="surrogateescape")
            print("stripped", log.relative_to(ROOT))


def problems(path):
    """git's default whitespace rules on a whole new file: trailing whitespace, space before tab in the indent, blank line at EOF."""
    found = []
    lines = (ROOT / path).read_text(errors="surrogateescape").split("\n")
    for number, line in enumerate(lines, 1):
        if re.search(r"[ \t\r]+$", line):
            found.append(f"{path}:{number}: trailing whitespace.")
        if re.match(r"^\t* +\t", line):
            found.append(f"{path}:{number}: space before tab in indent.")
    if len(lines) > 1 and lines[-1] == "" and lines[-2].strip() == "":
        found.append(f"{path}: new blank line at EOF.")
    return found


def check():
    status = [line for line in git("status", "--porcelain", "--untracked-files=all").stdout.split("\n") if line]
    mine = [line for line in status if line[3:] not in OTHERS and not line[3:].startswith("target/")]
    untracked = sorted(line[3:] for line in mine if line.startswith("??"))
    tracked = sorted(line[3:] for line in mine if not line.startswith("??") and (ROOT / line[3:]).exists())
    print(f"{len(tracked)} changed tracked paths and {len(untracked)} new paths of E8-T03")
    print("$ git diff --check -- <" + str(len(tracked)) + " paths: " + " ".join(tracked) + ">")
    result = subprocess.run(["git", "diff", "--check", "--", *tracked], cwd=ROOT, text=True, capture_output=True)
    print(result.stdout + result.stderr, end="")
    print(f"exit {result.returncode}")
    found = [problem for path in untracked for problem in problems(path)]
    print("$ git's whitespace rules on the " + str(len(untracked)) + " new files: " + " ".join(untracked))
    print("\n".join(found) if found else "no problem")
    return 1 if result.returncode or found else 0


if __name__ == "__main__":
    if sys.argv[1:] == ["strip"]:
        strip()
    elif sys.argv[1:] == ["check"]:
        sys.exit(check())
    else:
        sys.exit(__doc__)
