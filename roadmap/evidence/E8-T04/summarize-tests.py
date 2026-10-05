#!/usr/bin/env python3
"""Summarize the final clean Maven run without copying fixtures from test output."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

verification_exit = int(Path(sys.argv[1]).read_text().strip())
print(f"clean verify exit={verification_exit}")
changed = {"CardPaymentsIT", "PaymentCurlIT", "StripePaymentProviderTest", "StripeCallsTest", "E8ContractIT",
           "E8ResponseContractTest", "SignupGateFixesIT", "DashboardBuildersTest", "NotificationEngineIT",
           "S04ErrorContractTest", "NotificationMatrixTest", "NotificationCatalogContractTest", "CheckoutProviderFailureTest", "E3ContractIT",
           "TemplateVariableParityIT"}
required = {"ArchitectureTest", "AuditContractTest", "EventCatalogContractTest", "SignupCensusCorrectionsIT"}
found = set()
failed = verification_exit != 0
for lane in ("surefire", "failsafe"):
    totals = dict.fromkeys(("tests", "failures", "errors", "skipped"), 0)
    details = []
    paths = sorted(Path(f"target/{lane}-reports").glob("TEST-*.xml"))
    if not paths:
        raise SystemExit(f"Missing {lane} reports")
    for path in paths:
        root = ET.parse(path).getroot()
        counts = {key: int(root.attrib.get(key, 0)) for key in totals}
        for key, value in counts.items():
            totals[key] += value
        name = root.attrib["name"].rsplit(".", 1)[-1]
        if name in changed | required:
            found.add(name)
            details.append(name + ": " + ", ".join(f"{key}={value}" for key, value in counts.items()))
    print(lane + " totals: " + ", ".join(f"{key}={value}" for key, value in totals.items()))
    for detail in details:
        print("  " + detail)
    failed |= totals["failures"] != 0 or totals["errors"] != 0
missing = (changed | required) - found
if missing:
    print("Missing task/contract test classes: " + ", ".join(sorted(missing)))
    failed = True
sys.exit(1 if failed else 0)
