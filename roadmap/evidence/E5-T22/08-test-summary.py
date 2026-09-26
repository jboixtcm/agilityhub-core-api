import pathlib
import xml.etree.ElementTree as ET

# E5-T22: the totals of the last `./mvnw -q clean verify` (target/surefire-reports and target/failsafe-reports), one line
# per test class this task adds or changes, and the result of each test method it adds or changes.
CLASSES = ["SignupPlansFollowUpIT", "ActivityApiServiceTest", "ActivityRealCoreFollowUpsIT", "ListFieldsContractIT", "AuditQueriesIT",
           "ActivityIT", "E4ContractIT", "OpenApiRequiredContractTest", "E3ResponseContractTest", "OpenApiSnapshotTest"]
METHODS = ["R_05_19_T_04_09_eachPlanCarriesItsPriceLabelInTheReadersLocale",
           "R_04_09_T_04_21_theFamilyMembersOwnHiddenPlanIsListedAsCurrentInAddDogMode",
           "R_04_09_aMemberWithoutAPlanGetsTheOfferAndMustChooseOne",
           "R_07_08_aRankIsSentOnlyOnARowThePageReadAsWaitlisted",
           "R_07_08_withFieldsThePageStillReadsTheStateTheRankNeeds",
           "R_07_08_theRanksOfSeveralActivitiesComeFromOneReadOfTheWaitingEntries",
           "R_07_08_theMemberViewReadsTheWaitingEntriesOnceForAllItsActivities",
           "CONVENCIONS_API_4_withFieldsEveryUniversalListSendsTheRowIdAndTheRequestedKeysOnly",
           "CONVENCIONS_API_4_everyUniversalListPublishesExactlyTheFieldsItAccepts",
           "T_14_13_aChangeWithoutAStoredValueReadsNullAndAnInstructorsOriginReadsBackoffice",
           "T_07_21_idIsAFilterBothD7ListsPublish",
           "T_06_20_T_07_17_snapshotPublishesEveryOperationWithTypedResponsesAndListMetadata",
           "E1_T11_everySnapshotObjectDeclaresItsRequiredProperties",
           "T_07_21_aRegistrationListItemAlwaysSendsCancelReasonNullUntilItIsCancelled"]
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
lines.append("Classes this task adds or changes (the last two only through the fixture and the snapshot):")
for name in CLASSES:
    kind, full, values = per.get(name, ("?", name, [0, 0, 0, 0]))
    lines.append("  [%s] %s: tests=%d failures=%d errors=%d skipped=%d" % (kind, full, *values))
lines.append("E5-T22 test methods (added or changed; the last one only through its comment):")
for name in METHODS:
    owner, result = cases.get(name, ("?", "MISSING"))
    lines.append("  %s %s#%s" % (result, owner, name))
print("\n".join(lines))
