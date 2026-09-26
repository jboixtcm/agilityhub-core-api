import pathlib
import xml.etree.ElementTree as ET

# E5-T21: the totals of the last `./mvnw -q clean verify` (target/surefire-reports and target/failsafe-reports), one line
# per test class this task adds or changes, and the result of each test method it adds or changes.
CLASSES = ["SignupSecurityFixesIT", "SignupGateFixesIT", "E3GateFixesContractTest", "ClubDefinitionsIT", "E5PersistenceIT",
           "CatalogServiceRetryTest", "TransactionRetriesTest", "OpenApiSnapshotTest"]
METHODS = ["R_04_19_R_04_08_d2RemovesOneFileOfAPendingPublicSignupDog",
           "R_04_19_R_04_06_aReusedDogsValidationKeepsTheFileRemovedFromItsOwnCard",
           "R_04_19_sendingBackD2sDocumentsIsNoChange",
           "R_04_08_R_04_19_theFileLimitCountsOnlyTheNewUploadsOfARequest",
           "R_02_06_clubUpdateTaxIdIsNullableAndBlankOrNullClearsIt",
           "R_02_06_aClubDefinitionRefusesANullTaxId(Path)",  # a @TempDir parameter: JUnit reports the signature
           "R_04_10_T_04_14_theSepaMandateIsTheTextOfMockup19WithTheClubsLegalName",
           "R_05_07_R_05_08_aRingChangeInterruptedDuringItsBackoffStopsRetrying",
           "R_09_06_oneConflictCheckRetriesAWriteConflictADuplicateKeyOrATransientError",
           "R_15_19_theDeployBackfillGivesTheOldRingSlotLocksTheirExpiry"]
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
lines.append("Classes this task adds or changes (OpenApiSnapshotTest only through the regenerated snapshot):")
for name in CLASSES:
    kind, full, values = per.get(name, ("?", name, [0, 0, 0, 0]))
    lines.append("  [%s] %s: tests=%d failures=%d errors=%d skipped=%d" % (kind, full, *values))
lines.append("E5-T21 test methods (added, extended or renamed):")
for name in METHODS:
    owner, result = cases.get(name, ("?", "MISSING"))
    lines.append("  %s %s#%s" % (result, owner, name))
print("\n".join(lines))
