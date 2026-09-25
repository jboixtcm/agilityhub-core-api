import pathlib
import xml.etree.ElementTree as ET

# The test classes E5-T17 adds or changes, read from the reports of the last `./mvnw -q clean verify`.
CHANGED = {
    "surefire": ["RiskReviewQueryTest", "SchedulingPersistenceTest", "CountryProfileTest", "CatalogServiceRetryTest",
                 "TransactionRetriesTest", "SchedulingTransactionsTest", "TrainingApplicationTest", "E3GateFixesContractTest",
                 "E3ResponseContractTest", "OpenApiNullableEnumContractTest"],
    "failsafe": ["RiskReviewJobIT", "E5PersistenceIT", "CleanupJobIT", "SettingsIT", "ClubDefinitionsIT", "ActivityIT",
                 "SignupPaymentMethodsIT", "OpenApiSnapshotTest"],
}
# The E5-T17 test methods (added, extended or renamed), with their result in the reports.
METHODS = ["T_15_15_willCancelOrWillReviewOnlyWhileTheRiskReviewWillStillRunForTheClass",
           "T_15_15_E37_willCancelOnlyWhenTheRiskReviewWillReallyRunForTheClass",
           "T_15_15_T_14_05_theDashboardRiskCardIsTheRiskReviewFormA",
           "E5_T15_aRingChangeMetByAConflictIsRetriedAndThenCommits",
           "E5_T15_aRingChangeThatKeepsConflictingEndsInStaleVersionAfterItsBudget",
           "E5_T17_aRingChangeInterruptedDuringItsBackoffStopsRetrying",
           "E5_T15_businessAnswersAndOtherKindsAreNotRetried",
           "E5_T17_oneConflictCheckForTheRetriedWriters",
           "E5_T17_theSharedBackoffDrawsFiftyToOneHundredFiftyMilliseconds",
           "T_07_16_R_07_13_theAppRowsCarryStartAndEndTimesNullWhenAbsent",
           "T_15_27_aRunTheRunnerClosesFailedWhoseHookThrowsDoesNotKeepThePlatformCycle",
           "E5_T15_theStartupRunnerCreatesTheRingSlotCollectionOnceAndToleratesARace",
           "E5_T17_theStartupRunnerEnsuresTheRingSlotTtlIndexOnEveryStart",
           "T_15_27_R_15_19_ringSlotLocksExpireSevenDaysAfterTheirSlotThroughATtlIndex",
           "T_15_27_cleanupDeletesOnlyExpiredTechnicalDataAndKeepsTheLastFiveRuns",
           "T_15_27_cleanupIsBoundToItsClubAndKeepsTheLastFiveExecutionsNotDryRunsOrSkips",
           "R_02_06_aTaxIdOfSeparatorsOnlyIsRefusedAndOnlyBlankOrNullClearsIt",
           "R_02_06_clubApplyRefusesATaxIdOfSeparatorsOnlyAndOnlyBlankClearsIt",
           "INC_08_everyOptionalPropertyTheSignupViewsSendAsNullIsNullable",
           "INC_08_everyOptionalPropertyGetClubSendsAsNullIsNullable",
           "INC_08_getClubSendsEveryUnsetFieldAsANullTheSnapshotDeclares",
           "INC_08_theD2ViewAndTheSubmissionResultsDeclareEveryNullTheySend",
           "E3_T01_signupResponseFixturesRoundTripWithTheirNestedFieldsEnumsMoneyAndUtcDates",
           "R_04_10_reorderingThePaymentProvidersIsAChangeAndTheOfferFollowsIt",
           "R_02_06_aProfileStoresATaxIdInTheFormItsCheckReads",
           "R_02_06_anEsClubStoresASevenDigitDniPaddedAndBothWritingsAreTheSameId",
           "R_02_06_clubApplyStoresASevenDigitDniPaddedUnderEs",
           "E3_T10_theDocumentWriterAddsNullToTheEnumOfNullableSchemasOnly"]
lines = []
cases = {}
for kind in ("surefire", "failsafe"):
    totals = [0, 0, 0, 0]
    per = {}
    for report in sorted(pathlib.Path("target/" + kind + "-reports").glob("TEST-*.xml")):
        suite = ET.parse(report).getroot()
        values = [int(suite.get(key, 0)) for key in ("tests", "failures", "errors", "skipped")]
        totals = [a + b for a, b in zip(totals, values)]
        per[suite.get("name").rsplit(".", 1)[-1]] = (suite.get("name"), values)
        for case in suite.iter("testcase"):
            failed = any(child.tag in ("failure", "error", "skipped") for child in case)
            cases[case.get("name")] = (kind, case.get("classname").rsplit(".", 1)[-1], "FAIL" if failed else "PASS")
    lines.append("%s totals: tests=%d failures=%d errors=%d skipped=%d (%d classes)" % (kind, *totals, len(per)))
    for name in CHANGED[kind]:
        full, values = per[name]
        lines.append("  %s: tests=%d failures=%d errors=%d skipped=%d" % (full, *values))
lines.append("E5-T17 test methods:")
for name in METHODS:
    kind, owner, result = cases.get(name, ("?", "?", "MISSING"))
    lines.append("  %s %s %s#%s" % (result, kind, owner, name))
print("\n".join(lines))
