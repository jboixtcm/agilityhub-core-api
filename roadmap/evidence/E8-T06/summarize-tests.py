#!/usr/bin/env python3
"""Summarize a completed E8-T06 clean verification from Maven's XML reports."""
from pathlib import Path
import xml.etree.ElementTree as ET

CLASSES = set("""
ArchitectureTest ExpirationsJobTest PlayoffAdapterTest MigrationResetServiceTest
MigrationPhotoFetcherTest FakeWebhookCommandTest PaymentProviderContractTest
StripePaymentProviderTest PaymentProviderSelectionTest AuditContractTest
JobCatalogContractTest EventCatalogContractTest E8ScheduledProcessesIT
E8LeaveProcessesIT AccountingExportIT PlayoffMigrationIT E8ContractIT E5ContractIT
TemplateVariableParityIT DemoBillingSeedIT DemoPlanningSeedIT DemoScenarioSeedIT
SignupCensusCorrectionsIT IdempotencyIT JobFrameworkIT JobsApiIT BillingCycleIT
""".split())
FIELDS = ("tests", "failures", "errors", "skipped")
seen = set()
for lane in ("surefire", "failsafe"):
    reports = sorted(Path(f"target/{lane}-reports").glob("TEST-*.xml"))
    assert reports, f"Missing {lane} XML reports"
    totals = dict.fromkeys(FIELDS, 0)
    changed = []
    for report in reports:
        suite = ET.parse(report).getroot()
        values = {key: int(suite.get(key, "0")) for key in FIELDS}
        for key in FIELDS:
            totals[key] += values[key]
        name = suite.get("name").rsplit(".", 1)[-1]
        if name in CLASSES:
            seen.add(name)
            changed.append((name, values))
    print(f"{lane}: " + " ".join(f"{key}={totals[key]}" for key in FIELDS))
    for name, values in sorted(changed):
        print(f"  {name}: " + " ".join(f"{key}={values[key]}" for key in FIELDS))
    assert totals["failures"] == totals["errors"] == totals["skipped"] == 0, totals
assert seen == CLASSES, f"Missing task classes: {sorted(CLASSES - seen)}"
