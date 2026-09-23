"""E5-T01: totals of the Surefire (unit/contract) and Failsafe (integration) XML reports of the last ./mvnw verify."""
import glob
import xml.etree.ElementTree as ET

NEW = ["E5ContractIT", "E5PersistenceIT", "E5ResponseContractTest", "JobFrameworkIT", "JobFrameworkTwoInstancesIT", "JobOccurrencesTest",
       "ClockRuleTest", "OffsetClockTest", "ErrorCatalogContractTest", "EventCatalogContractTest", "AuditContractTest", "ArchitectureTest",
       "OpenApiRequiredContractTest", "MessageParityTest", "E2ContractIT", "E4ContractIT"]
for label, pattern in [("unit/contract (surefire)", "target/surefire-reports/TEST-*.xml"), ("integration (failsafe)", "target/failsafe-reports/TEST-*.xml")]:
    totals = [0, 0, 0, 0]
    for path in glob.glob(pattern):
        # verify runs OpenApiSnapshotTest under failsafe; a surefire copy only comes from bin/openapi-snapshot.
        if "surefire" in pattern and path.endswith("OpenApiSnapshotTest.xml"):
            continue
        suite = ET.parse(path).getroot()
        values = [int(suite.get(key, 0)) for key in ("tests", "failures", "errors", "skipped")]
        totals = [a + b for a, b in zip(totals, values)]
        name = suite.get("name").rsplit(".", 1)[-1]
        if name in NEW:
            names = sorted({case.get("name").split("(")[0].split("[")[0] for case in suite.iter("testcase")})
            print("  %-28s tests=%-3d failures=%d errors=%d skipped=%d  %s" % (name, *values, ", ".join(names)))
    print("%s: tests=%d failures=%d errors=%d skipped=%d" % (label, *totals))
