import pathlib
import xml.etree.ElementTree as ET

# E5-T23: the totals of the last `./mvnw -q clean verify` (target/surefire-reports and target/failsafe-reports), one line
# per test class this task adds or changes, and the result of each test method it adds or changes.
CLASSES = ["ClubDefinitionsIT", "ClubDefinitionCodecTest", "SignupSecurityFixesIT", "TransactionRetriesTest", "DemoSeedsIT"]
METHODS = ["T_04_14_T_02_12_canicSeedCarriesItsCashPaymentInstructions",
           "T_02_12_canicSeedEnablesDirectDebitAndCashWithoutConfiguringThem",
           "T_17_01_enablingSeededProvidersIsOneChangeAndKeepsTheirStoredConfiguration",
           "T_17_01_paymentProvidersAreNamesOrEnabledFlagsAndNeverCredentials",
           "T_17_01_storedCashInstructionsAreExportedPlainOrWrapped",
           "R_04_19_R_04_08_aD2EditMeetsTheCardRequirementUnlessItChangesTheCard",
           "R_04_19_R_04_06_aSubmissionRefusesAKeyRemovedFromTheReusedDogsOwnCard",
           "R_04_19_R_04_06_aReusedDogsMixedEditMergesOnlyTheTypesItChanges",
           "T_17_01_cliExportsYamlAndExitsWithoutOpeningTheConfiguredHttpPort",
           "T_02_12_canicMatchesBrandingAndParametersFixturesAndSecondApplyDoesNotWrite"]
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
            # A parameterized method is reported as `name(Type)`.
            cases[case.get("name").split("(")[0]] = (case.get("classname").rsplit(".", 1)[-1], "FAIL" if failed else "PASS")
    lines.append("%s totals: tests=%d failures=%d errors=%d skipped=%d (%d classes)" % (kind, *totals, count))
lines.append("Classes this task adds or changes (DemoSeedsIT through the consumer seed and TransactionRetriesTest through its Javadoc only):")
for name in CLASSES:
    kind, full, values = per.get(name, ("?", name, [0, 0, 0, 0]))
    lines.append("  [%s] %s: tests=%d failures=%d errors=%d skipped=%d" % (kind, full, *values))
lines.append("E5-T23 test methods (added or changed; the last two unchanged, they read the new seed and export):")
for name in METHODS:
    owner, result = cases.get(name, ("?", "MISSING"))
    lines.append("  %s %s#%s" % (result, owner, name))
print("\n".join(lines))
