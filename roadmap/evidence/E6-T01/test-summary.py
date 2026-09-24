"""E6-T01: totals of the Surefire (unit/contract) and Failsafe (integration) XML reports of the last ./mvnw verify, per class for the E6 ones."""
import glob
import xml.etree.ElementTree as ET

NEW = ["E6ContractIT", "E6PersistenceIT", "E6ResponseContractTest", "JobCatalogContractTest", "AttendanceWindowTest", "AttendanceStatusCalculatorTest",
       "AttendanceContractAccessTest", "FollowupContractAccessTest", "AttachmentServiceTest", "CalendarIT", "ErrorCatalogContractTest",
       "EventCatalogContractTest", "AuditContractTest", "ArchitectureTest", "OpenApiRequiredContractTest", "MessageParityTest", "OpenApiSnapshotTest",
       "E5ContractIT", "E4ContractIT", "CensusIT"]
for label, pattern in [("unit/contract (surefire)", "target/surefire-reports/TEST-*.xml"), ("integration (failsafe)", "target/failsafe-reports/TEST-*.xml")]:
    totals = [0, 0, 0, 0]
    for path in sorted(glob.glob(pattern)):
        # verify runs OpenApiSnapshotTest under failsafe; a surefire copy only comes from bin/openapi-snapshot.
        if "surefire" in pattern and path.endswith("OpenApiSnapshotTest.xml"):
            continue
        suite = ET.parse(path).getroot()
        values = [int(suite.get(key, 0)) for key in ("tests", "failures", "errors", "skipped")]
        totals = [a + b for a, b in zip(totals, values)]
        name = suite.get("name").rsplit(".", 1)[-1]
        if name in NEW:
            print("  %-32s tests=%-3d failures=%d errors=%d skipped=%d" % (name, *values))
    print("%s: tests=%d failures=%d errors=%d skipped=%d" % (label, *totals))
