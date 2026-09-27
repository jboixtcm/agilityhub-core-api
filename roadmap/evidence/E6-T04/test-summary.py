"""E6-T04: totals of the Surefire (unit/contract) and Failsafe (integration) XML reports of the last ./mvnw clean verify,
one line per test class the task adds or changes, and the test methods named after the task's spec ids."""
import glob
import re
import xml.etree.ElementTree as ET

CLASSES = ["NoShowNoticesJobIT", "ClassFinishingJobIT", "DemoAttendanceSeedIT", "BookingJobsTest", "NoShowNotificationTextsTest", "JobOccurrencesTest",
           "JobCatalogContractTest", "JobsApiIT", "JobFrameworkIT", "E6ContractIT", "ActivityIT", "ListFieldsContractIT", "DemoScenarioSeedIT",
           "DemoPlanningSeedIT", "ArchitectureTest", "AuditContractTest", "EventCatalogContractTest", "MessageParityTest", "OpenApiSnapshotTest",
           "CalendarIT", "AttendanceIT", "FollowupIT"]
TASK = ("NoShowNoticesJobIT", "ClassFinishingJobIT", "DemoAttendanceSeedIT", "BookingJobsTest", "NoShowNotificationTextsTest", "JobOccurrencesTest",
        "JobCatalogContractTest", "E6ContractIT", "ActivityIT", "AttendanceIT")
IDS = r"(T_15_16|T_15_26|T_10_26|T_10_11|T_10_19|T_10_04|T_15_13|T_15_18)"
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
            print("  %-30s tests=%-3d failures=%d errors=%d skipped=%d" % (name, *values))
        for case in suite.iter("testcase"):
            if not re.search(IDS, case.get("name")) or name not in TASK:
                continue
            ok = case.find("failure") is None and case.find("error") is None and case.find("skipped") is None
            for test_id in re.findall(IDS, case.get("name")):
                methods.setdefault(test_id.replace("_", "-"), []).append("%s.%s %s" % (name, re.sub(r"\[.*|\(.*", "", case.get("name")), "ok" if ok else "FAILED"))
    print("%s: tests=%d failures=%d errors=%d skipped=%d" % (label, *totals))
print("Spec-id test methods (the task's classes; T-10-11/T-10-19 integration halves are E6-T02's AttendanceIT plus bin/e6-smoke steps 3 and 7):")
for test_id in sorted(methods):
    for line in sorted(set(methods[test_id])):
        print("  %s  %s" % (test_id, line))
