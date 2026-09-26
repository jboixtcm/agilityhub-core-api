import pathlib
import xml.etree.ElementTree as ET

# E5-T26: the totals of the last `./mvnw -q clean verify` (target/surefire-reports and target/failsafe-reports), one line
# per test class this task adds or changes and the classes that exercise the routes it touches, and the result of each test
# method it adds or changes. Run from the repository root.
CLASSES = ["AttachmentServiceTest", "SignupSecurityFixesIT", "SignupIT", "SignupFollowUpFixesIT", "SignupS3UploadIT", "CensusIT",
           "ActivityIT", "TenantFilterTest", "CorsIT", "OpenApiSnapshotTest"]
METHODS = ["CONVENCIONS_API_5_R_04_27_signedRoutesRunWithTheGrantsClubAsTheTenant",
           "R_04_27_aMembersSignupUploadGrantCarriesTheAccount",
           "CONVENCIONS_API_5_localSignedUrlsAuthoriseThemselves",
           "R_04_27_R_04_08_aMembersAddDogUploadPassesWhileSignupIsClosedAndAnAnonymousOneDoesNot",
           "CONVENCIONS_API_5_R_04_27_theSignupUploadRouteTakesTheGrantsClubWhateverTheHostOrBearer",
           "CONVENCIONS_API_5_localDownloadsAnswerTheStoredTypeAndNameAndShowImagesInline",
           "CONVENCIONS_API_5_T_01_25_theSignedUploadRoutesPassTheCorsPreflightOfTheClubsWebOrigin",
           "CONVENCIONS_API_5_R_04_19_aSignedUploadUrlTakesAPutWithItsHeadersOnlyAndItsFileIsClaimed",
           "CONVENCIONS_API_5_signedDownloadUrlsAnswerWithoutABearerAndRefuseAnotherFilesSignature",
           "R_04_27_T_04_23_anonymousSignupRoutesAreClosedWithTheForm",
           "R_04_08_T_04_13_signupUploadUrlReturnsTheHeadersTheStorageSigned"]
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
lines.append("Classes this task changes, and the classes that exercise the routes it touches:")
for name in CLASSES:
    kind, full, values = per.get(name, ("?", name, [0, 0, 0, 0]))
    lines.append("  [%s] %s: tests=%d failures=%d errors=%d skipped=%d" % (kind, full, *values))
lines.append("E5-T26 test methods (added or changed) and the existing ones on the same routes:")
for name in METHODS:
    owner, result = cases.get(name, ("?", "MISSING"))
    lines.append("  %s %s#%s" % (result, owner, name))
print("\n".join(lines))
