import pathlib
import xml.etree.ElementTree as ET

# E5-T20: the totals of the last `./mvnw -q clean verify` (target/surefire-reports and target/failsafe-reports), one line
# per test class this task adds or changes, and the result of each test method it adds or changes.
CLASSES = ["ActivityRealCoreFollowUpsIT", "ListFieldsContractIT", "ActivityIT", "SettingsIT", "CalendarIT",
           "ParameterCatalogContractTest", "ActivityRulesTest", "E4ContractIT", "E4ResponseContractTest", "OpenApiSnapshotTest"]
METHODS = ["T_07_04_R_07_05_ringConflictsAnswersTheErrorsOfTheWindowThePublicationWouldRefuse",
           "T_02_03_everyKeyIsInItsDocumentedD11BlockAndOnlySystemKeysArePlatformOnly",
           "T_02_03_R_02_04_theD11BlocksFollowTheCatalogAndOnlyTheClubKeysAreEditable",
           "T_07_21_withFieldsBothD7ListsLeaveOutEveryKeyThatWasNotRequested",
           "CONVENCIONS_API_4_everyUniversalListPublishesExactlyTheFieldsItAccepts",
           "T_07_21_T_07_29_theD7ColumnsOfTheListAreTheActivityViewsOwnValues",
           "T_07_21_aRegistrationListItemAlwaysSendsCancelReasonNullUntilItIsCancelled",
           "R_07_08_T_07_15_aWaitlistedRegistrationReadsItsRankAmongTheWaitingEntries",
           "R_07_08_theWaitlistRankIsTheDenseRankOfThePositions",
           "T_07_21_theRegistrationsListAppliesOnlyTheFiltersOfTheQuery",
           "T_06_20_anUnknownDayGridViewIsAValidationErrorOnTheViewField",
           "T_06_20_T_07_17_snapshotPublishesEveryOperationWithTypedResponsesAndListMetadata",
           "T_06_20_T_07_17_formsKeepEveryDocumentedFieldDuringRoundTrip"]
lines, per, cases = [], {}, {}
for kind in ("surefire", "failsafe"):
    totals, count = [0, 0, 0, 0], 0
    for report in sorted(pathlib.Path("target/" + kind + "-reports").glob("TEST-*.xml")):
        suite = ET.parse(report).getroot()
        values = [int(suite.get(key, 0)) for key in ("tests", "failures", "errors", "skipped")]
        totals = [a + b for a, b in zip(totals, values)]
        count += 1
        per[suite.get("name").rsplit(".", 1)[-1]] = (kind, suite.get("name"), values)
        for case in suite.iter("testcase"):
            failed = any(child.tag in ("failure", "error", "skipped") for child in case)
            cases[case.get("name")] = (case.get("classname").rsplit(".", 1)[-1], "FAIL" if failed else "PASS")
    lines.append("%s totals: tests=%d failures=%d errors=%d skipped=%d (%d classes)" % (kind, *totals, count))
lines.append("Classes this task adds or changes (the last three only through the contract and its fixtures):")
for name in CLASSES:
    kind, full, values = per.get(name, ("?", name, [0, 0, 0, 0]))
    lines.append("  [%s] %s: tests=%d failures=%d errors=%d skipped=%d" % (kind, full, *values))
lines.append("E5-T20 test methods (added or changed; the last two only through the fixtures):")
for name in METHODS:
    owner, result = cases.get(name, ("?", "MISSING"))
    lines.append("  %s %s#%s" % (result, owner, name))
print("\n".join(lines))
