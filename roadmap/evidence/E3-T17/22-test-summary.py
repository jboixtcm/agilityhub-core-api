import pathlib
import xml.etree.ElementTree as ET

# E3-T17 round 2: the totals of the last `./mvnw -q clean verify` (target/surefire-reports and target/failsafe-reports),
# the classes this round touches or depends on, and the result of each round-2 test method.
CLASSES = ["SignupSecurityFixesIT", "SignupFollowUpFixesIT", "SignupGateFixesIT", "SignupIT", "CensusIT", "E3ContractIT",
           "E4ContractIT", "E6ContractIT", "OpenApiSnapshotTest", "CleanupJobIT", "SignupMinorFixesIT", "SignupPaymentMethodsIT",
           "MemberAggregatesIT", "AttachmentServiceTest"]
METHODS = ["R_04_19_T_04_12_aD2DocumentEditOfTheReusedDogReplacesOnlyTheTypesItSends",
           "R_04_19_aD2DocumentEditOfAPendingDogLeavesTheTypesItDoesNotSend",
           "R_04_06_R_04_23_T_04_19_theReusedDogsRecordIsFrozenWhileTheReadmissionIsPending",
           "T_04_12_aReadmissionWithoutACardKeepsTheDogsOwnCardAtValidation",
           "T_04_12_aReusedDogWithoutACardDocumentGetsTheEmptyCardAtValidation",
           "R_04_06_R_14_09_theReusedDogsSubmittedValuesAreAuditedLikeTheMembers",
           "R_15_19_T_04_19_T_15_27_p9KeepsAPendingReadmissionsFilesAndDeletesThemAfterTheRejection",
           "T_04_12_T_04_19_rejectedReadmissionLeavesTheRecordExactlyAsItWas",
           "T_04_12_theReusedDogWaitsInTheRequestUntilTheValidationAppliesItsValuesAndDocuments"]
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
lines.append("Classes of this round:")
for name in CLASSES:
    kind, full, values = per.get(name, ("?", name, [0, 0, 0, 0]))
    lines.append("  [%s] %s: tests=%d failures=%d errors=%d skipped=%d" % (kind, full, *values))
lines.append("E3-T17 round 2 test methods:")
for name in METHODS:
    owner, result = cases.get(name, ("?", "MISSING"))
    lines.append("  %s %s#%s" % (result, owner, name))
print("\n".join(lines))
