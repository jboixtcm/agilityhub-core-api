"""E7-T01 round 2: totals of the Surefire (unit/contract) and Failsafe (integration) XML reports of the last
./mvnw clean verify, one line per test class round 2 changes plus the suites that read or write notifications, and
the result of every test method round 2 adds."""
import glob
import xml.etree.ElementTree as ET

CHANGED = ["E7ContractIT", "E7PersistenceIT", "NotificationCatalogContractTest", "E7ResponseContractTest"]
READERS = ["SystemNotificationServiceIT", "SendGridWebhookIT", "EmailUnsubscribeIT", "NotificationEngineIT", "NotificationDispatcherIT",
           "NotificationSeedSnapshotTest", "TemplateRendererTest", "NotificationSpecTest", "EventCatalogContractTest", "ErrorCatalogContractTest",
           "OpenApiSnapshotTest", "OpenApiRequiredContractTest", "ArchitectureTest", "AuditContractTest", "IdentityCoreIT"]
NEW = ["WP_11_A_everyOperationPublishesTheAuthenticationItEnforces",
       "WP_11_A_anSmsIntentWrittenBeforeE7LoadsAndItsReplayWritesNoSecondRowBeforeAnyMigration",
       "WP_11_A_theMigrationConvertsARowTheWebhookMovedAfterTheCommandListedIt",
       "WP_11_A_theMigrationConvertsARowTheWebhookMovedBetweenTheConversionsReadAndWrite",
       "WP_11_A_aRowThatKeepsChangingUnderTheMigrationIsLeftForItsNextRun",
       "WP_11_A_theS11SeedTextsUseOnlyTheirCodesVariables",
       "WP_11_A_catalogMatchesTheDocumentRowByRowInBothDirections",
       "WP_11_A_everyEventAndVariableComesFromTheCatalogs",
       "WP_11_A_eventsHaveExactlyTheS11PayloadFieldsAndEnvelope"]
lines, results = {}, {}
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
            if case.get("name") in NEW:
                ok = case.find("failure") is None and case.find("error") is None and case.find("skipped") is None
                results[case.get("name")] = "%s.%s %s" % (name, case.get("name"), "ok" if ok else "FAILED")
    print("%s: tests=%d failures=%d errors=%d skipped=%d" % (label, *totals))
for title, classes in [("Changed by round 2", CHANGED), ("Suites that read or write notifications or the snapshot (unchanged)", READERS)]:
    print(title + ":")
    for name in classes:
        print(lines.get(name, "  %-34s MISSING" % name))
print("Round 2 test methods (new, or changed for E66):")
for name in NEW:
    print("  " + results.get(name, name + " MISSING"))
