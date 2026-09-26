import pathlib
import xml.etree.ElementTree as ET

# E5-T24: the totals of the last `./mvnw -q clean verify` (target/surefire-reports and target/failsafe-reports), one line
# per test class this task adds or changes, and the result of each test method it adds or changes. Run from the repository root.
CLASSES = ["SignupSecurityFixesIT", "CensusIT", "SignupIT", "SignupPlansFollowUpIT", "DemoSeedsIT", "UniversalListsIT",
           "AttachmentServiceTest", "CalendarIT", "PlanningIT", "E4ContractIT", "E5ContractIT", "ListFieldsContractIT",
           "ListContractValidationTest"]
METHODS = ["R_04_19_R_04_08_d2AddsAFileToAPendingSignupDogWithTheAdminsUpload",
           "R_04_19_R_04_06_d2AddsTheAdminsUploadToTheReusedDogAndTheValidationWritesIt",
           "CONVENCIONS_API_5_R_04_19_aSignedUploadUrlTakesAPutWithItsHeadersOnlyAndItsFileIsClaimed",
           "CONVENCIONS_API_5_signedDownloadUrlsAnswerWithoutABearerAndRefuseAnotherFilesSignature",
           "T_03_23_photoAndNoteAttachmentsAreBoundPrivateAndReplaySafe",
           "T_04_13_signedUploadClaimsOnlyExistingTenantFilesAndEnforcesLimits",
           "R_04_19_d2ClaimsOnlyTheCallersOwnDogDocumentUpload",
           "CONVENCIONS_API_5_localSignedUrlsAuthoriseThemselves",
           "R_04_06_R_04_07_theDemosLeftMemberHasAnInactiveChippedDogThatAReadmissionReuses",
           "T_03_42_fullDemoCountsDocumentsTeamTenantAndRepeat",
           "T_03_42_smallFixtureUsesSameGeneratorAndExistingCensusIsProtected",
           "R_14_09_anInstructorsAuditedActionIsStoredBackofficeAndItsEventKeepsInstructor",
           "CONVENCIONS_API_4_noOperationPublishesFieldsWithoutXFieldsAndEachExportPublishesItsLists",
           "CONVENCIONS_API_4_everyUniversalListPublishesExactlyTheFieldsItAccepts",
           "CONVENCIONS_API_4_withFieldsEveryUniversalListSendsTheRowIdAndTheRequestedKeysOnly",
           "CONVENCIONS_API_4_T_03_11_anExportsFieldsPickItsColumns",
           "CONVENCIONS_API_4_wellDeclaredListContractsStart",
           "CONVENCIONS_API_4_aMisdeclaredListContractStopsTheStartup",
           "R_04_09_aMemberWhoseOwnPlanIsGoneMustChooseOne",
           "R_04_09_theFamilyMembersOwnHiddenPlanIsListedAsCurrentInAddDogMode",
           "R_05_19_T_05_07_eachPlanCarriesItsPriceLabelInTheReadersLocale",
           "CONVENCIONS_API_4_everyEngineListPublishesTheFiltersItAccepts",
           "T_06_01_aTemplateClassWithoutAPlacementSendsANullPlacementIdUnderCourses",
           "T_06_20_T_07_17_snapshotPublishesEveryOperationWithTypedResponsesAndListMetadata",
           "T_08_47_T_09_24_T_15_29_snapshotPublishesEveryOperationWithTypedResponsesAndListMetadata"]
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
            cases[case.get("name").split("(")[0]] = (case.get("classname").rsplit(".", 1)[-1], "FAIL" if failed else "PASS")
    lines.append("%s totals: tests=%d failures=%d errors=%d skipped=%d (%d classes)" % (kind, *totals, count))
lines.append("Classes this task adds or changes:")
for name in CLASSES:
    kind, full, values = per.get(name, ("?", name, [0, 0, 0, 0]))
    lines.append("  [%s] %s: tests=%d failures=%d errors=%d skipped=%d" % (kind, full, *values))
lines.append("E5-T24 test methods (added or changed):")
for name in METHODS:
    owner, result = cases.get(name, ("?", "MISSING"))
    lines.append("  %s %s#%s" % (result, owner, name))
print("\n".join(lines))
