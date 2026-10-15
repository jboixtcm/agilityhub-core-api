#!/usr/bin/env python3
"""Check E9-T01 round 3 scope and continuity without writing git state."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

folder = Path("roadmap/evidence/E9-T01")
task = Path("roadmap/tasks/E9-T01.md")


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def inputs():
    names = subprocess.check_output([
        "git", "ls-files", "src", "pom.xml", "bin", ".mvn", "mvnw", "mvnw.cmd",
        "docs/specs", "docs/openapi/openapi.json", "docs/openapi/CHANGELOG.md", "CHANGELOG.md",
    ], text=True).splitlines()
    return {name: digest(name) for name in names if Path(name).is_file()}


if sys.argv[1:] == ["--capture"]:
    with (folder / "round3-tested-inputs.json").open("x") as output:
        json.dump(inputs(), output, indent=2)
        output.write("\n")
    print(f"Captured {len(inputs())} source, test, build, catalog and OpenAPI inputs before clean verification")
    sys.exit(0)

baseline = json.loads((folder / "round3-baseline.json").read_text())
before = json.loads(subprocess.check_output(["git", "show", baseline["base_commit"] + ":docs/openapi/openapi.json"], text=True))
after = json.loads(Path("docs/openapi/openapi.json").read_text())
methods = {"get", "post", "patch", "put", "delete", "head", "options", "trace"}


def operations(api):
    return {(path, method): value for path, item in api["paths"].items()
            for method, value in item.items() if method in methods}


old_ops, new_ops = operations(before), operations(after)
assert old_ops.keys() == new_ops.keys()
changed_ops = {key for key in old_ops if old_ops[key] != new_ops[key]}
assert changed_ops == {("/api/v1/courses/{id}", "get"), ("/api/v1/ring-setups/{id}", "get")}, changed_ops
old_schemas, new_schemas = before["components"]["schemas"], after["components"]["schemas"]
assert old_schemas.keys() == new_schemas.keys()
changed_schemas = {key for key in old_schemas if old_schemas[key] != new_schemas[key]}
assert changed_schemas == {"RingSetup", "MemberRingSetup"}, changed_schemas
for name, value in (("RingSetup", "STAFF"), ("MemberRingSetup", "MEMBER")):
    shape = new_schemas[name]
    assert shape["properties"]["view"]["const"] == value
    assert shape["properties"]["view"]["readOnly"] is True
    assert "view" in shape["required"]
    reverted = json.loads(json.dumps(shape))
    del reverted["properties"]["view"]
    reverted["required"].remove("view")
    assert reverted == old_schemas[name], name
print(f"Snapshot: {len(new_ops)} operations, {len(new_schemas)} schemas; 0 added or removed")
print("Only GET course detail, GET setup detail, RingSetup and MemberRingSetup changed")
print("Both setup schemas have a required, read-only view constant; existing fields are unchanged")

assert inputs() == json.loads((folder / "round3-tested-inputs.json").read_text())
print(f"All {len(inputs())} tested inputs are unchanged since clean verification started")
for name, expected in json.loads((folder / "round3-concurrent-inputs.json").read_text()).items():
    assert digest(name) == expected, name
    print("Concurrent organizer edit preserved:", name)
allowed = {
    "CHANGELOG.md", "docs/openapi/CHANGELOG.md", "docs/openapi/openapi.json",
    "roadmap/MESSAGES.md", "roadmap/STATUS.md", str(task),
    "src/main/java/com/agilityhub/core/configuration/E9ContractConfiguration.java",
    "src/main/java/com/agilityhub/core/courses/api/CourseContracts.java",
    "src/main/java/com/agilityhub/core/courses/api/CoursesController.java",
    "src/main/java/com/agilityhub/core/courses/api/RingSetupsController.java",
    "src/main/java/com/agilityhub/core/courses/application/CourseContractGuards.java",
    "src/test/java/com/agilityhub/core/configuration/E9ContractIT.java",
    "src/test/java/com/agilityhub/core/configuration/E9ResponseContractTest.java",
    "src/test/resources/fixtures/contracts/e9-ring-responses.json",
    *json.loads((folder / "round3-concurrent-inputs.json").read_text()).keys(),
}
changed = subprocess.check_output(["git", "diff", "--name-only"], text=True).splitlines()
untracked = subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard"], text=True).splitlines()
assert all(name in allowed or name.startswith(str(folder) + "/") for name in changed + untracked)
print("Changed-file scope is limited to E9-T01 and the preserved organizer edits")
prefix = Path("roadmap/MESSAGES.md").read_bytes()[:baseline["messages_prefix_bytes"]]
assert hashlib.sha256(prefix).hexdigest() == baseline["messages_prefix_sha256"]
original = subprocess.check_output(["git", "show", baseline["base_commit"] + ":roadmap/tasks/E9-T01.md"], text=True)
current = task.read_text()
assert current.split("## Organizer verification", 1)[1] == original.split("## Organizer verification", 1)[1]
assert current.split("# E9-T01", 1)[1].split("## Executor report", 1)[0] == original.split("# E9-T01", 1)[1].split("## Executor report", 1)[0]
round1 = original.split("## Executor report\n", 1)[1].split("### Round 2 report\n", 1)[0]
assert (folder / "round1-executor-report.md").read_text().endswith(round1)
print("Task instructions and Organizer verification unchanged; Round 1 report archived verbatim")
assert task.stat().st_size < 120000
print(f"Task report: {task.stat().st_size} bytes, below 120000")

if "### Round 3 report" in current:
    report = current.split("### Round 3 report", 1)[1].split("## Organizer verification", 1)[0]
    for stem in ("61-round3-openapi-first", "62-round3-openapi-second", "63-round3-openapi-cmp",
                 "65-round3-clean-verify", "66-round3-test-summary", "67-round3-schema-sha256",
                 "68-round3-diff-check"):
        metadata = json.loads((folder / (stem + ".json")).read_text())
        assert metadata["exit_code"] == 0, stem
        tail = "".join((folder / (stem + ".log")).read_text().splitlines(keepends=True)[-40:]).rstrip("\n")
        assert metadata["command"] in report, stem
        assert tail in report, stem
    print("Every final required verification command, exit and literal last 40 lines is in the Round 3 report")
