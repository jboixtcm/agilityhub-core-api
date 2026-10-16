"""Totals of target/surefire-reports and target/failsafe-reports, plus the classes E9-T04 adds or changes."""
import glob
import sys
import xml.etree.ElementTree as ET

WATCHED = ["BuildSessionRulesTest", "BuildSessionExportTest", "CourseSchemasTest", "RequiresModuleInterceptorTest", "E9ResponseContractTest",
           "OpenApiSnapshotTest", "ArchitectureTest", "BuildSessionsIT", "E9ContractIT", "SecurityInventoryIT", "RingSetupsIT", "PlacementsIT",
           "CleanupJobIT", "JobsApiIT", "E9PersistenceIT"]
print(sys.argv[1] if len(sys.argv) > 1 else "From target/surefire-reports and target/failsafe-reports.")
for kind in ["surefire", "failsafe"]:
    totals = [0, 0, 0, 0]
    classes = 0
    rows = {}
    for path in sorted(glob.glob(f"target/{kind}-reports/TEST-*.xml")):
        root = ET.parse(path).getroot()
        classes += 1
        counts = [int(root.get(key, 0)) for key in ("tests", "failures", "errors", "skipped")]
        totals = [a + b for a, b in zip(totals, counts)]
        name = root.get("name").split(".")[-1]
        if name in WATCHED:
            rows[name] = counts
    print(f"{kind}: {classes} classes; tests={totals[0]}, failures={totals[1]}, errors={totals[2]}, skipped={totals[3]}")
    for name in sorted(rows):
        c = rows[name]
        print(f"  {name}: tests={c[0]}, failures={c[1]}, errors={c[2]}, skipped={c[3]}")
