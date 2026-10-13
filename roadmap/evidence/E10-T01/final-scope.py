#!/usr/bin/env python3
"""Validate the resumed task's evidence and preserve unrelated roadmap changes."""
from pathlib import Path
import hashlib
import json
import re
import subprocess

root = Path(__file__).resolve().parent
manifest = json.loads((root / "36-input-manifest.json").read_text())
for entry in manifest:
    if entry["path"] != "CHANGELOG.md":
        assert hashlib.sha256(Path(entry["path"]).read_bytes()).hexdigest() == entry["sha256"], entry["path"]
print(f"All {len(manifest)-1} non-changelog inputs still match the clean run; changelog records its result.")
for entry in json.loads((root / "42-external-scope.json").read_text()):
    assert hashlib.sha256(Path(entry["path"]).read_bytes()).hexdigest() == entry["sha256"], entry["path"]
    print(entry["path"] + ": preserved")
task = Path("roadmap/tasks/E10-T01.md")
before = subprocess.check_output(["git", "show", "HEAD:roadmap/tasks/E10-T01.md"]).decode()
assert before.split("## Organizer verification", 1)[1] == task.read_text().split("## Organizer verification", 1)[1]
assert task.stat().st_size < 120 * 1024
print("Organizer verification unchanged; task report below 120 KB.")
report = task.read_text()
for name in ("37-clean-verify", "43-test-summary", "38-openapi-diff", "40-checkpoint-ci-status", "39-checkpoint-ci", "41-checkpoint-source-match", "44-current-input-check", "45-evidence-redaction", "46-evidence-secrets"):
    assert (root / (name + ".exit")).read_text().strip() == "0"
    assert (root / (name + ".command.txt")).read_text().strip() in report
    tail = "\n".join((root / (name + ".log")).read_text().splitlines()[-40:])
    assert "```text\n" + tail + "\n```" in report, name
print("Every final verification command, zero exit and literal output tail is recorded.")
old_log = subprocess.check_output(["git", "show", "HEAD:roadmap/evidence/E10-T01/28-clean-verify.log"]).decode()
expected = re.sub(r'(?P<prefix>traceId[=:]\s*)[A-Za-z0-9_-]{8,}', r'\g<prefix>[truncated]', old_log)
expected = re.sub(r'(?P<prefix>"traceId"\s*:\s*")[^"\n]+', r'\g<prefix>[truncated]', expected)
expected = re.sub(r'(?P<prefix>recipientHash=)[A-Za-z0-9_-]+', r'\g<prefix>[truncated]', expected)
assert expected == (root / "28-clean-verify.log").read_text()
print("Inherited attempt 28 retains every line; its only edits truncate synthetic identifiers/hashes.")
