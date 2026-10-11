#!/usr/bin/env python3
"""Summarize the completed clean verification and the task's required test classes."""
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

run = json.loads(Path(sys.argv[1]).read_text())
print(f"Verification command: {run['command']}")
print(f"Verification exit code: {run['exit_code']}")
assert run["exit_code"] == 0, "No successful verification to summarize"
wanted = {
    "ArchitectureTest", "E9ContractIT", "E9ResponseContractTest", "E9PersistenceIT",
    "OpenApiRequiredContractTest", "OpenApiNullableEnumContractTest", "ListFieldsContractIT",
    "SecurityInventoryIT", "CourseSchemasTest", "EventCatalogContractTest", "ErrorCatalogContractTest",
    "AuditContractTest", "E2ContractIT", "ParameterCatalogContractTest", "ParameterValidatorTest",
    "ClubDefinitionsIT", "NotificationCatalogContractTest", "OpenApiSnapshotTest", "E5BackOfficeContractTest",
}
seen = set()
for folder in ("surefire-reports", "failsafe-reports"):
    totals = dict.fromkeys(("tests", "failures", "errors", "skipped"), 0)
    rows = []
    reports = sorted(Path("target", folder).glob("TEST-*.xml"))
    assert reports, f"Missing {folder}"
    for path in reports:
        root = ET.parse(path).getroot()
        counts = {key: int(root.attrib.get(key, 0)) for key in totals}
        for key in totals:
            totals[key] += counts[key]
        name = root.attrib["name"].rsplit(".", 1)[-1]
        if name in wanted:
            seen.add(name)
            rows.append(f"  {name}: " + " ".join(f"{key}={value}" for key, value in counts.items()))
    print(f"{folder} totals: " + " ".join(f"{key}={value}" for key, value in totals.items()))
    print("\n".join(rows))
    assert totals["failures"] == totals["errors"] == totals["skipped"] == 0
assert seen == wanted, f"Missing task classes: {wanted - seen}"
