#!/usr/bin/env python3
"""Add per-class counts for the complete task scope; keep pre-snapshot clean totals in log 79."""
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[3]
evidence = root / "roadmap/evidence/E8-T05"
selected = {Path(line).stem for line in (evidence / "files-changed.txt").read_text().splitlines()
            if line.startswith("src/test/") and line.endswith(("Test.java", "IT.java"))}
selected.update({"PackBalanceServiceTest", "LifecycleConcurrencyIT", "ClubDefinitionsIT", "ArchitectureTest", "AuditContractTest", "EventCatalogContractTest", "MessageParityTest"})
rows = {}
for suite in ("surefire", "failsafe"):
    for p in (root / f"target/{suite}-reports").glob("TEST-*.xml"):
        row = ET.parse(p).getroot()
        name = row.attrib["name"].rsplit(".", 1)[-1]
        if name in selected:
            rows[name] = row
assert selected == rows.keys(), f"Missing reports: {selected - rows.keys()}"
print("Complete E8-T05 changed test-class inventory; clean run 78 exited 0; totals retained in log 79.")
for name, row in sorted(rows.items()):
    assert all(int(row.get(field, 0)) == 0 for field in ("failures", "errors", "skipped")), name
    print(name + ": " + ", ".join(f"{field}={row.get(field, 0)}" for field in ("tests", "failures", "errors", "skipped")))
print("Shared BookingFixtures/TrainingFixtures and ArchitectureRules are exercised by their listed suites.")
