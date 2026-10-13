#!/usr/bin/env python3
"""Check the resumed task's evidence and scope without changing repository state."""
import hashlib
import json
from pathlib import Path
import subprocess


def git(*args):
    return subprocess.check_output(["git", *args], text=True)


task = Path("roadmap/tasks/E9-T01.md")
report = task.read_text()
assert task.stat().st_size < 120_000, "Task exceeds the report size limit"
original = git("show", "0b481a3:roadmap/tasks/E9-T01.md")
marker = "## Organizer verification\n"
assert report.split(marker, 1)[1] == original.split(marker, 1)[1]
assert report.split("## Executor report", 1)[0].split("# E9-T01", 1)[1] == original.split("## Executor report", 1)[0].split("# E9-T01", 1)[1]
print("Organizer verification and task instructions: unchanged")

allowed = {"CHANGELOG.md", "roadmap/STATUS.md", "roadmap/MESSAGES.md", str(task)}
changed = set(git("diff", "--name-only", "0b481a3").splitlines())
untracked = set(git("ls-files", "--others", "--exclude-standard").splitlines())
unexpected = {p for p in changed | untracked if p not in allowed and not p.startswith("roadmap/evidence/E9-T01/")}
concurrent = json.loads(Path("roadmap/evidence/E9-T01/concurrent-edits.json").read_text())
assert unexpected == concurrent.keys(), f"Unexpected changed files: {sorted(unexpected)}"
for path, expected in concurrent.items():
    actual = hashlib.sha256(git("diff", "0b481a3", "--", path).encode()).hexdigest()
    assert actual == expected, f"Concurrent edit changed during handoff: {path}"
    print(f"Concurrent edit preserved: {path}")
print("Changed-file scope: E9-T01 evidence, report, changelog, messages and generated status only")
assert not git("diff", "e5b98c1", "--", "src", "pom.xml", "bin", ".mvn", "mvnw", "mvnw.cmd", "docs/openapi/openapi.json")
print("Current source, tests, build inputs and OpenAPI: identical to clean verification 42")

folder = Path("roadmap/evidence/E9-T01")
for stem in ("42-round2-clean-verify", "45-round2-test-summary", "47-round2-openapi-first", "50-round2-openapi-second"):
    meta = json.loads((folder / (stem + ".json")).read_text())
    assert meta["exit_code"] == 0, f"Unsuccessful evidence: {stem}"
    tail = "\n".join((folder / (stem + ".log")).read_text().splitlines()[-40:])
    assert tail in report, f"Literal tail missing from report: {stem}"
    assert meta["command"] in report, f"Exact command missing from report: {stem}"
    print(f"{stem}: exit 0; exact command and literal final tail present")

api = json.loads(Path("docs/openapi/openapi.json").read_text())
verbs = {"get", "post", "put", "patch", "delete", "head", "options", "trace"}
count = sum(method in verbs for item in api["paths"].values() for method in item)
routes = json.loads(Path("src/test/resources/fixtures/contracts/e9-routes.json").read_text())
assert len(routes) == 42
assert all(route["method"].lower() in api["paths"][route["path"]] for route in routes)
print(f"Snapshot: {count} total operations, {len(api['components']['schemas'])} schemas; all 42 S16 operations present")
print(f"Task report size: {task.stat().st_size} bytes (limit 120000)")
