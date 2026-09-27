"""E7-T01: totals of the Surefire (unit/contract) and Failsafe (integration) XML reports of the last ./mvnw clean verify,
one line per test class the task adds or changes, and the test methods named after the task's spec ids."""
import glob
import re
import xml.etree.ElementTree as ET

ADDED = ["NotificationCatalogContractTest", "NotificationSpecTest", "MessagingDocumentsTest", "E7ContractIT", "E7PersistenceIT", "E7ResponseContractTest"]
CHANGED = ["EventCatalogContractTest", "ErrorCatalogContractTest", "OpenApiRequiredContractTest", "ListFieldsContractIT", "ArchitectureTest"]
UNCHANGED = ["AuditContractTest", "OpenApiSnapshotTest", "OpenApiNullableEnumContractTest", "E2ContractIT", "SystemNotificationServiceIT", "SendGridWebhookIT",
             "MemberAggregatesIT", "WaitlistIT", "BookingsIT", "CalendarIT", "TrainingIT", "ActivityIT", "SignupIT", "SignupGateFixesIT", "SignupFollowUpFixesIT",
             "SignupSecurityFixesIT", "SignupMinorFixesIT", "FollowupIT", "RiskReviewJobIT", "NoShowNoticesJobIT", "WeekOpeningJobIT", "BookingJobsIT",
             "JobFrameworkIT", "IdentityCoreIT", "SingleClassCheckoutIT", "BookingConcurrencyIT", "AttendanceIT", "ClassFinishingJobIT", "DemoAttendanceSeedIT"]
IDS = r"(T_11_\d\d|WP_11_A)"
lines = {}
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
        lines[name] = "  %-34s tests=%-3d failures=%d errors=%d skipped=%d" % (name, *values)
        for case in suite.iter("testcase"):
            if not re.search(IDS, case.get("name")) or name not in ADDED + CHANGED:
                continue
            ok = case.find("failure") is None and case.find("error") is None and case.find("skipped") is None
            for test_id in sorted(set(re.findall(IDS, case.get("name")))):
                methods.setdefault(test_id.replace("_", "-"), []).append("%s.%s %s" % (name, re.sub(r"\[.*|\(.*", "", case.get("name")), "ok" if ok else "FAILED"))
    print("%s: tests=%d failures=%d errors=%d skipped=%d" % (label, *totals))
for title, classes in [("Added by E7-T01", ADDED), ("Changed by E7-T01", CHANGED), ("Unchanged suites that read or write notifications", UNCHANGED)]:
    print(title + ":")
    for name in classes:
        print(lines.get(name, "  %-34s MISSING" % name))
print("Spec-id test methods of the added/changed classes (parameterized cases collapsed):")
for test_id in sorted(methods):
    for line in sorted(set(methods[test_id])):
        print("  %s  %s" % (test_id, line))
