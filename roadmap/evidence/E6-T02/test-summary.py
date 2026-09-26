"""E6-T02: totals of the Surefire (unit/contract) and Failsafe (integration) XML reports of the last ./mvnw clean verify,
plus one line per test class the task adds or changes and the T-10 test methods it names."""
import glob
import re
import xml.etree.ElementTree as ET

CLASSES = ["AttendanceTransitionsTest", "NoticeRulesTest", "AttendanceMetricsTest", "HistoryRulesTest", "AttendanceWindowTest",
           "ArchitectureTest", "AuditContractTest", "EventCatalogContractTest", "MessageParityTest", "OpenApiRequiredContractTest",
           "E6ResponseContractTest", "AttendanceIT", "InstructorAggregatesIT", "E6ContractIT", "ListFieldsContractIT", "OpenApiSnapshotTest",
           "BookingsIT", "WaitlistIT", "BookingConcurrencyIT", "CalendarIT", "TrainingIT", "ActivityIT"]
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
            match = re.match(r"(T_10_\d\d|R_08_10)", case.get("name"))
            if match:
                ok = case.find("failure") is None and case.find("error") is None and case.find("skipped") is None
                methods.setdefault(match.group(1).replace("_", "-"), []).append("%s.%s %s" % (name, re.sub(r"\[.*", "", case.get("name")), "ok" if ok else "FAILED"))
    print("%s: tests=%d failures=%d errors=%d skipped=%d" % (label, *totals))
print("T-10 test methods (parameterized cases collapsed):")
for test_id in sorted(methods):
    for line in sorted(set(methods[test_id])):
        print("  %s  %s" % (test_id, line))
