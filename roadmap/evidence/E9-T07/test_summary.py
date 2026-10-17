"""Totals of target/surefire-reports and target/failsafe-reports, plus the classes E9-T07 adds or changes (and every failed case)."""
import glob
import sys
import xml.etree.ElementTree as ET

WATCHED = ["E9ResponseContractTest", "ArchitectureTest", "RingSetupsIT", "RingGeometryIT", "CatalogsIT", "BuildSessionsIT", "E9ContractIT", "E2ContractIT",
           "SecurityInventoryIT", "OpenApiSnapshotTest", "PlacementsIT", "CourseLibraryIT", "DemoCoursesSeedIT"]
print(sys.argv[1] if len(sys.argv) > 1 else "From target/surefire-reports and target/failsafe-reports.")
for kind in ["surefire", "failsafe"]:
    totals = [0, 0, 0, 0]
    classes = 0
    rows = {}
    failed = []
    for path in sorted(glob.glob(f"target/{kind}-reports/TEST-*.xml")):
        root = ET.parse(path).getroot()
        classes += 1
        counts = [int(root.get(key, 0)) for key in ("tests", "failures", "errors", "skipped")]
        totals = [a + b for a, b in zip(totals, counts)]
        name = root.get("name").split(".")[-1]
        if name in WATCHED:
            rows[name] = counts
        for case in root.iter("testcase"):
            for problem in ("failure", "error"):
                if case.find(problem) is not None:
                    failed.append(f"{name}.{case.get('name')} [{problem}]")
    print(f"{kind}: {classes} classes; tests={totals[0]}, failures={totals[1]}, errors={totals[2]}, skipped={totals[3]}")
    for name in sorted(rows):
        c = rows[name]
        print(f"  {name}: tests={c[0]}, failures={c[1]}, errors={c[2]}, skipped={c[3]}")
    for line in failed:
        print(f"  FAILED {line}")
