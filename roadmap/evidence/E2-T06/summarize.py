#!/usr/bin/env python3
"""Summarize the E2-T06 verification artifacts without reading fixture secrets."""
from pathlib import Path
import xml.etree.ElementTree as ET

for directory in ("surefire-reports", "failsafe-reports"):
    totals = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
    for path in Path("target", directory).glob("TEST-*.xml"):
        if directory == "surefire-reports" and "OpenApiSnapshotTest" in path.name:
            continue  # The snapshot script ran it separately; verify runs it with Failsafe.
        suite = ET.parse(path).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, 0))
    print(directory + ": " + ", ".join(f"{key}={value}" for key, value in totals.items()))

report = ET.parse("target/site/jacoco/jacoco.xml").getroot()
for package in report.findall("package"):
    name = package.get("name")
    if not ("/clubs/census/" in name or "/clubs/followup/" in name):
        continue
    if name.endswith("persistence"):
        continue
    counters = []
    for counter in package.findall("counter"):
        if counter.get("type") in ("LINE", "BRANCH"):
            covered, missed = int(counter.get("covered")), int(counter.get("missed"))
            counters.append(f"{counter.get('type')}={covered}/{covered + missed} ({covered / (covered + missed):.2%})")
    print(name + ": " + ", ".join(counters))

print("S03 census and attachment tests:")
for directory in ("surefire-reports", "failsafe-reports"):
    for path in sorted(Path("target", directory).glob("TEST-*.xml")):
        if not any(part in path.name for part in ("clubs.census", "clubs.followup")):
            continue
        suite = ET.parse(path).getroot()
        for test in suite.findall("testcase"):
            print(suite.get("name").split(".")[-1] + "." + test.get("name"))
