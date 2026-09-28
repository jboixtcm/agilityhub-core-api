"""E7-T02: totals of the Surefire (unit/contract) and Failsafe (integration) XML reports of the last ./mvnw clean verify,
one line per test class the task adds or changes, and the test methods named after the task's spec ids (T-11-xx)."""
import glob
import re
import xml.etree.ElementTree as ET

CLASSES = [
    # new (E7-T02)
    "ChannelResolverTest", "SmsTextTest", "TwilioSmsSenderTest", "WebPushSenderTest", "VariableFormatterTest", "TemplateRendererTest",
    "RecipientResolverTest", "NotificationEmailRendererTest", "NotificationSeedSnapshotTest", "MessagingPortsTest", "MessagingApplicationTest",
    "MessagingConfigurationTest", "NotificationEngineIT", "NotificationDispatcherIT", "EmailUnsubscribeIT",
    # changed (E7-T02) and the suites the Verification names
    "SendGridWebhookIT", "NoShowNoticesJobIT", "SystemNotificationServiceIT", "NotificationCatalogContractTest", "E7ContractIT", "E7PersistenceIT",
    "EventCatalogContractTest", "ErrorCatalogContractTest", "ArchitectureTest", "AuditContractTest", "MessageParityTest", "OpenApiSnapshotTest",
    # round 2: new and changed
    "NotificationLinksTest", "MessagingDocumentsTest", "BookingsIT"]
IDS = r"(T_11_\d\d)"
methods = {}
rules = set()
failed = []
for label, pattern in [("unit/contract (surefire)", "target/surefire-reports/TEST-*.xml"), ("integration (failsafe)", "target/failsafe-reports/TEST-*.xml")]:
    totals = [0, 0, 0, 0]
    lines = []
    for path in sorted(glob.glob(pattern)):
        # verify runs OpenApiSnapshotTest under failsafe; a surefire copy only comes from bin/openapi-snapshot.
        if "surefire" in pattern and path.endswith("OpenApiSnapshotTest.xml"):
            continue
        suite = ET.parse(path).getroot()
        values = [int(suite.get(key, 0)) for key in ("tests", "failures", "errors", "skipped")]
        totals = [a + b for a, b in zip(totals, values)]
        name = suite.get("name").rsplit(".", 1)[-1]
        if name in CLASSES:
            lines.append("  %-32s tests=%-3d failures=%d errors=%d skipped=%d" % (name, *values))
        for case in suite.iter("testcase"):
            ok = case.find("failure") is None and case.find("error") is None and case.find("skipped") is None
            if not ok:
                failed.append("%s.%s" % (name, case.get("name")))
            if name in CLASSES and re.match(r"R_1[01]_\d\d_", case.get("name")):
                rules.add("%s.%s %s" % (name, re.sub(r"\[.*|\(.*", "", case.get("name")), "ok" if ok else "FAILED"))
            for test_id in re.findall(IDS, case.get("name")):
                methods.setdefault(test_id.replace("_", "-"), []).append("%s.%s %s" % (name, re.sub(r"\[.*|\(.*", "", case.get("name")), "ok" if ok else "FAILED"))
    print("%s: tests=%d failures=%d errors=%d skipped=%d" % (label, *totals))
    print("\n".join(lines))
print("Not passed: %s" % (", ".join(failed) if failed else "none"))
print("T-11-xx test methods (parameterized cases collapsed):")
for test_id in sorted(methods):
    for line in sorted(set(methods[test_id])):
        print("  %s  %s" % (test_id, line))
print("R-11-xx / R-10-xx test methods of these classes (round 2's among them; parameterized cases collapsed):")
for line in sorted(rules):
    print("  " + line)
