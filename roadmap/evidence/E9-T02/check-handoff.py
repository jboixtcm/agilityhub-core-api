#!/usr/bin/env python3
"""Check the blocked handoff's scope, protected sections and literal evidence."""
import json
from pathlib import Path
import subprocess


def git(*arguments):
    return subprocess.check_output(["git", *arguments], text=True)


task_path = "roadmap/tasks/E9-T02.md"
report = Path(task_path).read_text()
original = git("show", "HEAD:" + task_path)
assert report.split("## Organizer verification", 1)[1] == original.split("## Organizer verification", 1)[1]
assert Path("roadmap/MESSAGES.md").read_text().startswith(git("show", "HEAD:roadmap/MESSAGES.md"))
assert len(Path(task_path).read_bytes()) < 120_000
assert "status: blocked" in report
expected = {task_path, "roadmap/STATUS.md", "roadmap/MESSAGES.md", "CHANGELOG.md"}
changed = set(git("diff", "--name-only").splitlines())
changed.update(git("ls-files", "--others", "--exclude-standard").splitlines())
assert all(path in expected or path.startswith("roadmap/evidence/E9-T02/") for path in changed), changed
for attempt in ("01-baseline-clean-verify", "05-baseline-ci-status", "06-baseline-ci-failure",
                "08-ci-baseline-match", "09-test-summary", "10-diff-summary"):
    prefix = Path("roadmap/evidence/E9-T02", attempt)
    metadata = json.loads(prefix.with_suffix(".json").read_text())
    tail = "\n".join(prefix.with_suffix(".log").read_text().splitlines()[-40:])
    assert metadata["command"] in report, attempt + ": missing exact command"
    assert f"exit **{metadata['exit_code']}**" in report, attempt + ": missing exit"
    assert tail in report, attempt + ": missing literal tail"
    print(f"{attempt}: command, exit {metadata['exit_code']} and literal final tail present")
print("Scope: E9-T02 evidence/report, generated status, append-only messages and changelog only.")
print("Organizer verification and other task files: unchanged.")
print(f"Task size: {Path(task_path).stat().st_size} bytes; below 120000.")
print("Final task status: blocked.")
