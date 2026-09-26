import pathlib
import xml.etree.ElementTree as ET

# E5-T19: the totals of the last `./mvnw -q clean verify` (target/surefire-reports and target/failsafe-reports), one line
# per test class this task adds or changes, and the result of each test method it adds or changes.
CLASSES = ["SignupSecurityFixesIT", "AttachmentServiceTest", "E3ResponseContractTest"]
METHODS = ["R_04_19_T_04_12_d2AddsAndRemovesOneFileOfTheReusedDogsSubmittedCard",
           "R_04_19_aD2EditOfAPendingDogKeepsTheFilesWhoseKeysItSendsBack",
           "R_04_19_R_04_08_d2RemovesOneFileOfAPendingPublicSignupDog",
           "R_04_06_R_04_08_T_04_13_aReusedDogsOwnCardMeetsTheCardRequirementAtSubmission",
           "R_04_06_R_04_08_d2WithdrawsTheSubmittedCardOfAReusedDogWithItsOwnCard",
           "T_04_12_aReusedDogWithoutACardDocumentGetsTheEmptyCardAtValidation",
           "R_04_08_R_04_19_aClaimedSignupFileHasAPlainIdThatIsNotItsStorageKey",
           "E3_T01_signupResponseFixturesRoundTripWithTheirNestedFieldsEnumsMoneyAndUtcDates"]
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
lines.append("Classes this task adds or changes:")
for name in CLASSES:
    kind, full, values = per.get(name, ("?", name, [0, 0, 0, 0]))
    lines.append("  [%s] %s: tests=%d failures=%d errors=%d skipped=%d" % (kind, full, *values))
lines.append("E5-T19 test methods (added or changed; the last one only through its fixture):")
for name in METHODS:
    owner, result = cases.get(name, ("?", "MISSING"))
    lines.append("  %s %s#%s" % (result, owner, name))
print("\n".join(lines))
