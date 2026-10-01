"""E8-T01 evidence: test totals from target/surefire-reports and target/failsafe-reports, one line per test class this task adds
or changes, and the JaCoCo line/branch coverage of the gated packages it touches (target/site/jacoco/jacoco.csv)."""
import csv
import glob
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
CLASSES = ["E8ContractIT", "E8PersistenceIT", "E8ResponseContractTest", "BankAccountVaultTest", "ErrorCatalogContractTest", "EventCatalogContractTest",
           "OpenApiRequiredContractTest", "ArchitectureTest", "AuditContractTest", "ListFieldsContractIT", "E2ContractIT", "E3ContractIT",
           "E5BackOfficeContractTest", "OpenApiSnapshotTest", "MessageParityTest", "ParameterCatalogContractTest",
           # Round 2 (01-10): the new and changed test classes.
           "StripeWebhookSignaturesTest", "PlayoffMigrationIT", "PlayoffAdapterTest", "ClubDefinitionsIT"]
PACKAGES = ["com/agilityhub/core/payments/application", "com/agilityhub/core/payments/domain", "com/agilityhub/core/payments/api",
            "com/agilityhub/core/clubs/census/application", "com/agilityhub/core/clubs/census/domain", "com/agilityhub/core/clubs/census/api",
            "com/agilityhub/core/platform/application", "com/agilityhub/core/shared/application/lists", "com/agilityhub/core/platform/application/definition",
            "com/agilityhub/core/migration/application", "com/agilityhub/core/clubs/bookings/application"]

for name, folder in (("unit/contract (surefire)", "surefire-reports"), ("integration (failsafe)", "failsafe-reports")):
    totals = [0, 0, 0, 0]
    for path in glob.glob(str(ROOT / "target" / folder / "TEST-*.xml")):
        suite = ET.parse(path).getroot()
        for i, key in enumerate(("tests", "failures", "errors", "skipped")):
            totals[i] += int(suite.get(key))
    print(f"{name}: tests={totals[0]} failures={totals[1]} errors={totals[2]} skipped={totals[3]}")
for path in sorted(glob.glob(str(ROOT / "target" / "*-reports" / "TEST-*.xml"))):
    suite = ET.parse(path).getroot()
    if suite.get("name").rsplit(".", 1)[-1] in CLASSES:
        print(f"  {suite.get('name')}: tests={suite.get('tests')} failures={suite.get('failures')} errors={suite.get('errors')} skipped={suite.get('skipped')}")
coverage = {}
with open(ROOT / "target/site/jacoco/jacoco.csv", newline="") as report:
    for row in csv.DictReader(report):
        key = row["PACKAGE"].replace(".", "/")
        values = coverage.setdefault(key, [0, 0, 0, 0])
        values[0] += int(row["LINE_COVERED"]); values[1] += int(row["LINE_MISSED"])
        values[2] += int(row["BRANCH_COVERED"]); values[3] += int(row["BRANCH_MISSED"])
for package in PACKAGES:
    covered, missed, bcovered, bmissed = coverage.get(package, [0, 0, 0, 0])
    lines = 100.0 * covered / max(1, covered + missed)
    branches = 100.0 * bcovered / max(1, bcovered + bmissed) if bcovered + bmissed else 100.0
    print(f"coverage {package.replace('/', '.')}: lines {lines:.1f} % branches {branches:.1f} %")
