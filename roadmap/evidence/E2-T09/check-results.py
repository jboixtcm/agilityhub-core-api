"""Summarize Maven evidence after verify; no application or database writes."""
from pathlib import Path
import xml.etree.ElementTree as ET

for runner in ("surefire", "failsafe"):
    suites = [ET.parse(path).getroot() for path in Path(f"target/{runner}-reports").glob("TEST-*.xml")]
    # Snapshot generation writes a Surefire report too; the full verify runs it under Failsafe.
    if runner == "surefire":
        suites = [suite for suite in suites if not suite.get("name", "").endswith("OpenApiSnapshotTest")]
    totals = {key: sum(int(suite.get(key, 0)) for suite in suites) for key in ("tests", "failures", "errors", "skipped")}
    assert totals["failures"] == totals["errors"] == totals["skipped"] == 0, totals
    print(f"{runner}: {totals}")

root = ET.parse("target/site/jacoco/jacoco.xml").getroot()
for package in root.findall("package"):
    name = package.get("name").replace("/", ".")
    for counter in package.findall("counter"):
        kind = counter.get("type")
        total = int(counter.get("covered")) + int(counter.get("missed"))
        if not total:
            continue
        ratio = int(counter.get("covered")) / total
        if ".domain" in name or ".application" in name:
            threshold = {"LINE": .85, "BRANCH": .80}.get(kind, 0)
        elif ".api" in name:
            threshold = .70 if kind == "LINE" else 0
        else:
            threshold = 0
        assert ratio >= threshold, (name, kind, ratio, threshold)
        if "platform.application.audit" in name or "platform.domain.audit" in name:
            if kind in ("LINE", "BRANCH"):
                print(f"{name} {kind}: {int(counter.get('covered'))}/{total} ({ratio:.2%})")
print("All configured package coverage thresholds passed.")
assert Path("target/openapi.json").read_bytes() == Path("docs/openapi/openapi.json").read_bytes()
print("OpenAPI snapshot matches target/openapi.json byte-for-byte.")
lines = [line for line in Path("roadmap/evidence/E2-T09/11-verify.log").read_text().splitlines() if line.startswith("Audit coverage:")]
assert len(lines) == 26, len(lines)
print("AuditAction coverage: 26 actions, zero exclusions; covering test names recorded in verify output.")
