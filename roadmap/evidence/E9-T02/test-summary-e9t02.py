import csv
import glob
import xml.etree.ElementTree as ET

CHANGED = ["CourseLibraryIT", "RingGeometryIT", "E9ContractIT", "ListFieldsContractIT", "E9ResponseContractTest", "RingGeometryRulesTest",
           "CourseModelFactsTest", "CourseListingTest", "CourseJsonConverterTest", "CourseSchemasTest", "AuditContractTest", "ArchitectureTest"]
print("Command: ./mvnw -q clean verify (via heavy.sh), exit 0 (log 14-clean-verify.log)")
for folder in ["surefire-reports", "failsafe-reports"]:
    totals = [0, 0, 0, 0]
    rows = {}
    for path in glob.glob("target/" + folder + "/TEST-*.xml"):
        suite = ET.parse(path).getroot()
        values = [int(suite.get(k)) for k in ("tests", "failures", "errors", "skipped")]
        totals = [a + b for a, b in zip(totals, values)]
        name = suite.get("name").split(".")[-1]
        if name in CHANGED:
            rows[name] = values
    print(f"{folder}: tests={totals[0]}, failures={totals[1]}, errors={totals[2]}, skipped={totals[3]}")
    for name in sorted(rows):
        t, f, e, s = rows[name]
        print(f"  {name}: tests={t}, failures={f}, errors={e}, skipped={s}")
print("JaCoCo (target/site/jacoco/jacoco.csv), courses packages:")
lines = {}
for row in csv.DictReader(open("target/site/jacoco/jacoco.csv")):
    if ".courses." in row["PACKAGE"] + ".":
        p = lines.setdefault(row["PACKAGE"], [0, 0, 0, 0])
        p[0] += int(row["LINE_MISSED"]); p[1] += int(row["LINE_COVERED"]); p[2] += int(row["BRANCH_MISSED"]); p[3] += int(row["BRANCH_COVERED"])
for package in sorted(lines):
    lm, lc, bm, bc = lines[package]
    branches = f"{100 * bc / (bm + bc):.1f}%" if bm + bc else "n/a"
    print(f"  {package}: lines {100 * lc / (lm + lc):.1f}% ({lc}/{lm + lc}), branches {branches} ({bc}/{bm + bc})")
