"""E6-T03: totals of the Surefire (unit/contract) and Failsafe (integration) XML reports of the last ./mvnw clean verify,
one line per test class the task adds or changes, and the test methods named after the task's spec ids."""
import glob
import re
import xml.etree.ElementTree as ET

CLASSES = ["FollowupIT", "FollowupRulesTest", "FollowupNotificationsTest", "FollowupNotificationTextsTest", "FollowupProjectionTest", "FollowupServiceTest",
           "FollowupTransactionsTest", "AttachmentServiceTest", "FollowupContractAccessTest", "ArchitectureTest", "AuditContractTest", "EventCatalogContractTest",
           "MessageParityTest", "E6ResponseContractTest", "E2ResponseContractTest", "E6ContractIT", "ListFieldsContractIT", "AttendanceIT",
           "InstructorAggregatesIT", "CensusIT", "DashboardIT", "DemoScenarioSeedIT", "OpenApiSnapshotTest", "OpenApiRequiredContractTest",
           # round 2 (27-09)
           "E6PersistenceIT", "E3GateFixesContractTest", "ListQueryTest", "ListContractValidationTest"]
IDS = r"(T_10_06|T_10_15|T_10_16|T_10_17|T_10_18|T_10_22|T_10_25|T_10_14|T_10_33|T_10_21|R_04_06|R_10_10)"
methods = {}
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
        if name in CLASSES:
            print("  %-32s tests=%-3d failures=%d errors=%d skipped=%d" % (name, *values))
        for case in suite.iter("testcase"):
            if not re.search(IDS, case.get("name")) or not (name.startswith(("Followup", "AttachmentService")) or name in ("E6ContractIT", "AttendanceIT", "ListQueryTest")):
                continue
            ok = case.find("failure") is None and case.find("error") is None and case.find("skipped") is None
            for test_id in re.findall(IDS, case.get("name")):
                methods.setdefault(test_id.replace("_", "-"), []).append("%s.%s %s" % (name, re.sub(r"\[.*|\(.*", "", case.get("name")), "ok" if ok else "FAILED"))
    print("%s: tests=%d failures=%d errors=%d skipped=%d" % (label, *totals))
print("Spec-id test methods of this task (FollowupIT, the follow-up unit tests, E6ContractIT, AttendanceIT; parameterized cases collapsed):")
for test_id in sorted(methods):
    for line in sorted(set(methods[test_id])):
        print("  %s  %s" % (test_id, line))
