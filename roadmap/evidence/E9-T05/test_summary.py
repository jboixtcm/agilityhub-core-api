"""Totals of target/surefire-reports and target/failsafe-reports, plus the classes E9-T05 adds or changes.

Run from the repository root: python3 roadmap/evidence/E9-T05/test_summary.py "<title>" [--since=<log>]
`--since=<log>` keeps only the reports written after that log file was created (a targeted run without `clean` leaves older
reports in place) and prints every class it keeps; otherwise every report counts and only the watched classes are listed.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

WATCHED = ["RingSetupRulesTest", "DemoCoursesSeederTest", "OpenApiSnapshotTest", "ArchitectureTest", "E9ResponseContractTest", "E4ResponseContractTest",
           "PlanningIT", "TemplateVariableParityIT", "RingSetupsIT", "PlacementsIT", "TrainingIT", "DemoCoursesSeedIT", "E9ContractIT"]
args = [a for a in sys.argv[1:] if not a.startswith("--since=")]
since = next((os.stat(a.split("=", 1)[1]).st_birthtime for a in sys.argv[1:] if a.startswith("--since=")), None)
everything = since is not None
print(args[0] if args else "From target/surefire-reports and target/failsafe-reports.")
for kind in ["surefire", "failsafe"]:
    totals = [0, 0, 0, 0]
    classes = 0
    rows = {}
    for path in sorted(glob.glob(f"target/{kind}-reports/TEST-*.xml")):
        if since is not None and os.stat(path).st_mtime < since:
            continue
        root = ET.parse(path).getroot()
        classes += 1
        counts = [int(root.get(key, 0)) for key in ("tests", "failures", "errors", "skipped")]
        totals = [a + b for a, b in zip(totals, counts)]
        name = root.get("name").split(".")[-1]
        if everything or name in WATCHED:
            rows[name] = counts
    print(f"{kind}: {classes} classes; tests={totals[0]}, failures={totals[1]}, errors={totals[2]}, skipped={totals[3]}")
    for name in sorted(rows):
        c = rows[name]
        print(f"  {name}: tests={c[0]}, failures={c[1]}, errors={c[2]}, skipped={c[3]}")
