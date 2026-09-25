#!/usr/bin/env python3
"""Summarise target/surefire-reports and target/failsafe-reports (totals, per class, failing test cases)."""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
classes = sys.argv[1].split(",") if len(sys.argv) > 1 and sys.argv[1] else []
cases = "--cases" in sys.argv


def report(kind):
    folder = ROOT / "target" / f"{kind}-reports"
    totals = [0, 0, 0, 0]
    rows = []
    for path in sorted(folder.glob("TEST-*.xml")):
        suite = ET.parse(path).getroot()
        numbers = [int(suite.get(k, 0)) for k in ("tests", "failures", "errors", "skipped")]
        totals = [a + b for a, b in zip(totals, numbers)]
        name = suite.get("name")
        if not classes or name.split(".")[-1] in classes:
            rows.append(f"  {name}: tests={numbers[0]} failures={numbers[1]} errors={numbers[2]} skipped={numbers[3]}")
            if cases:
                for case in suite.findall("testcase"):
                    problem = case.find("failure")
                    if problem is None:
                        problem = case.find("error")
                    state = "PASS" if problem is None else "FAIL"
                    detail = "" if problem is None else " :: " + (problem.get("message") or "").replace("\n", " ")[:300]
                    rows.append(f"    {state} {case.get('name')}{detail}")
    count = len(list(folder.glob("TEST-*.xml")))
    print(f"{kind}: {count} classes, tests={totals[0]} failures={totals[1]} errors={totals[2]} skipped={totals[3]}")
    print("\n".join(rows))


for kind in ("surefire", "failsafe"):
    report(kind)
