import pathlib
import xml.etree.ElementTree as ET

# The test classes E5-T18 adds or changes, read from the reports of the last `./mvnw -q clean verify`.
CHANGED = {
    "surefire": ["SchedulingPersistenceTest", "TransactionRetriesTest", "CatalogServiceRetryTest"],
    "failsafe": ["SignupGateFixesIT", "E5PersistenceIT", "SettingsIT", "ClubDefinitionsIT", "OpenApiSnapshotTest"],
}
# The E5-T18 test methods (added or renamed), with their result in the reports.
METHODS = ["R_04_10_T_04_14_theSepaMandateIsTheTextOfMockup19WithTheClubsLegalName",
           "R_15_19_ringSlotLocksExpireSevenDaysAfterTheirSlotThroughATtlIndex",
           "R_15_19_theDeployBackfillGivesTheOldRingSlotLocksTheirExpiry",
           "R_15_19_anotherIndexUnderTheTtlNameStaysAStartupFailure",
           "R_15_19_theStartupRunnerEnsuresTheRingSlotTtlIndexOnEveryStart",
           "R_15_19_anIndexWithTheTtlNameOnAnotherKeyStaysAStartupFailure",
           "R_09_06_R_09_13_oneConflictCheckRetriesAWriteConflictADuplicateKeyOrATransientError",
           "R_07_08_R_08_07_theSharedBackoffDrawsFiftyToOneHundredFiftyMilliseconds",
           "R_05_08_aRingChangeInterruptedDuringItsBackoffStopsRetrying",
           "R_02_06_aTaxIdOfSpacesOnlyClearsItLikeEmptyAndNull",
           "R_02_06_clubApplyClearsTheTaxIdWithSpacesOnlyLikeEmpty"]
# The names E5-T18 renames: none may remain in the reports.
OLD = ["T_15_27_R_15_19_ringSlotLocksExpireSevenDaysAfterTheirSlotThroughATtlIndex",
       "E5_T17_theStartupRunnerEnsuresTheRingSlotTtlIndexOnEveryStart",
       "E5_T17_oneConflictCheckForTheRetriedWriters",
       "E5_T17_theSharedBackoffDrawsFiftyToOneHundredFiftyMilliseconds",
       "E5_T17_aRingChangeInterruptedDuringItsBackoffStopsRetrying"]
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
lines.append("E5-T18 test methods:")
for name in METHODS:
    kind, owner, result = cases.get(name, ("?", "?", "MISSING"))
    lines.append("  %s %s %s#%s" % (result, kind, owner, name))
lines.append("Renamed away (must be absent):")
for name in OLD:
    lines.append("  %s %s" % ("ABSENT" if name not in cases else "STILL PRESENT", name))
print("\n".join(lines))
