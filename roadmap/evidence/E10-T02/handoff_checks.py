"""Verify executor scope, retained evidence and report excerpts before handoff."""
from pathlib import Path
import subprocess

task = Path("roadmap/tasks/E10-T02.md")
report = task.read_text()
original = subprocess.check_output(["git", "show", "HEAD:" + str(task)], text=True)
assert report.split("## Organizer verification", 1)[1] == original.split("## Organizer verification", 1)[1]
assert task.stat().st_size < 120_000
changed = subprocess.check_output(["git", "diff", "--name-only", "HEAD"], text=True).splitlines()
for name in changed:
    if name.startswith("roadmap/"):
        assert name.startswith("roadmap/evidence/E10-T02/") or name in {
            str(task), "roadmap/STATUS.md", "roadmap/MESSAGES.md"
        }, "Unexpected roadmap edit: " + name
print("Only E10-T02 roadmap files, generated STATUS and append-only MESSAGES are changed.")
messages = subprocess.check_output(["git", "show", "HEAD:roadmap/MESSAGES.md"], text=True)
assert Path("roadmap/MESSAGES.md").read_text().startswith(messages)
print("Organizer verification is unchanged; MESSAGES retains its original prefix.")
logs = sorted(p for p in Path("roadmap/evidence/E10-T02").glob("*.log") if p.name != "42-handoff-check.log")
ignored = subprocess.run(["git", "check-ignore", *map(str, logs)], capture_output=True, text=True)
assert ignored.returncode == 1 and not ignored.stdout, "Evidence ignored: " + ignored.stdout
for log in logs:
    assert log.with_suffix(".command.txt").exists()
    if not log.name.startswith("15-"):
        assert log.with_suffix(".exit").exists(), "Missing exit: " + str(log)
print(f"All {len(logs)} attempt logs are publishable; only inherited attempt 15 has no exit.")
for stem in ("21-openapi", "35-clean-verify", "36-head-ci", "37-test-summary", "38-input-check", "39-openapi-scope", "41-evidence-secrets"):
    prefix = Path("roadmap/evidence/E10-T02", stem)
    command = prefix.with_suffix(".command.txt").read_text().strip()
    tail = "\n".join(prefix.with_suffix(".log").read_text().splitlines()[-40:])
    assert command in report, "Missing exact command: " + stem
    assert tail in report, "Missing literal tail: " + stem
print("Final verification commands and literal last-40-line excerpts match their retained logs.")
subprocess.run(["git", "diff", "--check", "--", "src", "docs/openapi", "CHANGELOG.md", "roadmap/MESSAGES.md", "roadmap/STATUS.md", "roadmap/evidence/E10-T02/*.py"], check=True)
print("Source, snapshot, handoff documentation and evidence scripts pass git diff --check.")
print(f"Task report: {task.stat().st_size} bytes (limit 120000).")
