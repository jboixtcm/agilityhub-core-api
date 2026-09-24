"""E5-T03: totals of the Surefire (unit/contract) and Failsafe (integration) XML reports of the last ./mvnw verify,
the test methods of the waiting-list and booking classes, and the per-package coverage of the packages this task touched."""
import csv
import glob
import xml.etree.ElementTree as ET

SHOWN = ["WaitlistRulesTest", "WaitlistIT", "BookingConcurrencyIT", "BookingsIT", "BookingPortsTest", "E5ContractIT",
         "EventCatalogContractTest", "AuditContractTest", "ArchitectureTest", "MessageParityTest"]
for label, pattern in [("unit/contract (surefire)", "target/surefire-reports/TEST-*.xml"), ("integration (failsafe)", "target/failsafe-reports/TEST-*.xml")]:
    totals = [0, 0, 0, 0]
    for path in sorted(glob.glob(pattern)):
        if "surefire" in pattern and path.endswith("OpenApiSnapshotTest.xml"):
            continue
        suite = ET.parse(path).getroot()
        values = [int(suite.get(key, 0)) for key in ("tests", "failures", "errors", "skipped")]
        totals = [a + b for a, b in zip(totals, values)]
        name = suite.get("name").rsplit(".", 1)[-1]
        if name in SHOWN:
            names = sorted({case.get("name").split("(")[0].split("[")[0] for case in suite.iter("testcase")})
            print("  %-26s tests=%-3d failures=%d errors=%d skipped=%d" % (name, *values))
            if name.startswith("Waitlist") or name == "BookingConcurrencyIT":
                for method in names:
                    print("      " + method)
    print("%s: tests=%d failures=%d errors=%d skipped=%d" % (label, *totals))

PACKAGES = ["com.agilityhub.core.clubs.bookings.domain", "com.agilityhub.core.clubs.bookings.application",
            "com.agilityhub.core.clubs.bookings.application.ports", "com.agilityhub.core.clubs.bookings.api",
            "com.agilityhub.core.clubs.bookings.persistence", "com.agilityhub.core.clubs.messaging.application",
            "com.agilityhub.core.clubs.messaging.persistence"]
sums = {}
with open("target/site/jacoco/jacoco.csv") as source:
    for row in csv.DictReader(source):
        if row["PACKAGE"] in PACKAGES:
            s = sums.setdefault(row["PACKAGE"], [0, 0, 0, 0])
            for i, key in enumerate(["LINE_MISSED", "LINE_COVERED", "BRANCH_MISSED", "BRANCH_COVERED"]):
                s[i] += int(row[key])
print("coverage (merged unit + integration, jacoco.csv):")
for package in PACKAGES:
    lm, lc, bm, bc = sums.get(package, [0, 0, 0, 0])
    line = lc / (lm + lc) if lm + lc else 1.0
    branch = bc / (bm + bc) if bm + bc else 1.0
    print("  %-52s lines %5.1f %%  branches %5.1f %%" % (package, line * 100, branch * 100))
