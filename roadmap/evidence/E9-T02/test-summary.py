#!/usr/bin/env python3
"""Summarize the current clean verification, including classes with failures."""
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

metadata = json.loads(Path(sys.argv[1]).read_text())
print(f"Verification exit code: {metadata['exit_code']}")
for directory in ("surefire-reports", "failsafe-reports"):
    reports = sorted(Path("target", directory).glob("TEST-*.xml"))
    totals = dict.fromkeys(("tests", "failures", "errors", "skipped"), 0)
    failed_classes = []
    for report in reports:
        suite = ET.parse(report).getroot()
        counts = {key: int(suite.get(key, "0")) for key in totals}
        for key, count in counts.items():
            totals[key] += count
        if counts["failures"] or counts["errors"]:
            failed_classes.append((suite.get("name"), counts))
    if not reports:
        print(f"{directory}: NOT REACHED (no test reports from the clean run)")
        continue
    print(f"{directory}: classes={len(reports)}, " + ", ".join(f"{key}={value}" for key, value in totals.items()))
    for name, counts in failed_classes:
        print(f"  {name}: " + ", ".join(f"{key}={value}" for key, value in counts.items()))
print("No product or test classes added or changed in this task session.")
