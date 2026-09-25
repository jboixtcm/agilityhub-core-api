import pathlib
import xml.etree.ElementTree as ET

# The test classes E5-T16 adds or changes, read from the reports of the last `./mvnw -q clean verify`.
CHANGED = {
    "surefire": ["E3GateFixesContractTest", "NullableReferencesTest", "OpenApiRequiredContractTest", "CountryProfileTest",
                 "OpenApiNullableEnumContractTest"],
    "failsafe": ["SettingsIT", "ClubDefinitionsIT", "DashboardIT", "SignupPaymentMethodsIT", "E3ContractIT", "OpenApiSnapshotTest"],
}
lines = []
for kind in ("surefire", "failsafe"):
    totals = [0, 0, 0, 0]
    per = {}
    for report in sorted(pathlib.Path("target/" + kind + "-reports").glob("TEST-*.xml")):
        suite = ET.parse(report).getroot()
        values = [int(suite.get(key, 0)) for key in ("tests", "failures", "errors", "skipped")]
        totals = [a + b for a, b in zip(totals, values)]
        per[suite.get("name").rsplit(".", 1)[-1]] = (suite.get("name"), values)
    lines.append("%s totals: tests=%d failures=%d errors=%d skipped=%d (%d classes)" % (kind, *totals, len(per)))
    for name in CHANGED[kind]:
        full, values = per[name]
        lines.append("  %s: tests=%d failures=%d errors=%d skipped=%d" % (full, *values))
print("\n".join(lines))
