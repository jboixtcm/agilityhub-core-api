import pathlib
import xml.etree.ElementTree as ET

# The test classes E3-T17 adds or changes, read from the reports of the last `./mvnw -q clean verify`.
CHANGED = {
    "surefire": [],
    "failsafe": ["SignupSecurityFixesIT", "SignupFollowUpFixesIT", "SignupGateFixesIT", "SignupIT", "E3ContractIT",
                 "OpenApiSnapshotTest", "CleanupJobIT", "SignupMinorFixesIT", "SignupPaymentMethodsIT"],
}
# The E3-T17 test methods (added or extended), with their result in the failsafe reports.
METHODS = ["T_04_12_T_04_19_rejectedReadmissionLeavesTheRecordExactlyAsItWas",
           "T_04_12_theReusedDogWaitsInTheRequestUntilTheValidationAppliesItsValuesAndDocuments",
           "T_04_12_aReadmissionWithoutACardEmitsDogDocumentPendingOnlyAtValidation",
           "T_04_19_aNewDogOfARejectedReadmissionIsStampedAndTheMembersOldDogIsUntouched",
           "R_15_19_T_04_19_p9KeepsAPendingReadmissionsFilesAndDeletesThemAfterTheRejection",
           "R_04_06_T_04_12_T_04_19_queuedReadmissionsKeepTheirOwnLocaleDogPlanAndTotal",
           "T_04_16_foundFamilyClaimProposesAndValidatesTheHiddenFamilyFare",
           "T_04_23_addDogReplaysTheSameCreatedResponseForTheSameIdempotencyKey",
           "T_04_25_T_04_28_snapshotPublishesSchemasSecurityLimitsHeadersAndErrors"]
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
        if kind == "failsafe":
            for case in suite.iter("testcase"):
                failed = any(child.tag in ("failure", "error", "skipped") for child in case)
                cases[case.get("name")] = (case.get("classname").rsplit(".", 1)[-1], "FAIL" if failed else "PASS")
    lines.append("%s totals: tests=%d failures=%d errors=%d skipped=%d (%d classes)" % (kind, *totals, len(per)))
    for name in CHANGED[kind]:
        full, values = per[name]
        lines.append("  %s: tests=%d failures=%d errors=%d skipped=%d" % (full, *values))
lines.append("E3-T17 test methods:")
for name in METHODS:
    owner, result = cases.get(name, ("?", "MISSING"))
    lines.append("  %s %s#%s" % (result, owner, name))
print("\n".join(lines))
