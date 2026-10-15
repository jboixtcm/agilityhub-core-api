#!/usr/bin/env python3
"""Round 2 summary of the clean run (target/) against the HEAD-product run of the inherited E8-T12 classes (target/e10t02-red)."""
import pathlib, sys, xml.etree.ElementTree as ET

root = pathlib.Path(__file__).resolve().parents[3]
CHANGED = ["com.agilityhub.core.platform.api.PlatformConsoleIT", "com.agilityhub.core.payments.api.ConsoleProviderLockIT",
           "com.agilityhub.core.configuration.E10ContractIT", "com.agilityhub.core.configuration.E10ResponseContractTest",
           "com.agilityhub.core.configuration.E2ContractIT", "com.agilityhub.core.platform.application.audit.AuditContractTest",
           "com.agilityhub.core.platform.application.ConsoleTransactionsTest",
           "com.agilityhub.core.payments.application.stripe.StripePaymentProviderTest", "com.agilityhub.core.arch.ArchitectureTest",
           "com.agilityhub.core.clubs.catalogs.api.TeamIT", "com.agilityhub.core.clubs.census.api.CensusIT"]

def suites(directory):
    result = {}
    for path in sorted(directory.glob("TEST-*.xml")):
        suite = ET.parse(path).getroot()
        failing = [case.get("classname") + "#" + case.get("name") for case in suite.iter("testcase")
                   if case.find("failure") is not None or case.find("error") is not None]
        result[suite.get("name")] = ({k: int(suite.get(k)) for k in ("tests", "failures", "errors", "skipped")}, failing)
    return result

failing = set(); ok = True
for kind in ("surefire-reports", "failsafe-reports"):
    data = suites(root / "target" / kind)
    totals = {k: sum(v[0][k] for v in data.values()) for k in ("tests", "failures", "errors", "skipped")}
    print(f"{kind}: " + ", ".join(f"{k}={v}" for k, v in totals.items()))
    for name in CHANGED:
        if name in data:
            print(f"  {name}: " + ", ".join(f"{k}={v}" for k, v in data[name][0].items()))
    for counts, cases in data.values():
        failing.update(cases)
print("failing cases in the clean run:", len(failing))
for case in sorted(failing):
    print("  " + case)
head = set()
for counts, cases in suites(root / "target" / "e10t02-red" / "target" / "surefire-reports").values():
    head.update(cases)
print("same classes on HEAD's product code (attempt 58):", len(head), "failing")
inherited = failing <= head and all(case.split("#")[0].endswith(("PaymentIntentLedgerTest", "CapturedPaymentInvariantsIT")) for case in failing)
for name in CHANGED:
    for kind in ("surefire-reports", "failsafe-reports"):
        report = root / "target" / kind / f"TEST-{name}.xml"
        if report.exists():
            suite = ET.parse(report).getroot()
            ok &= suite.get("failures") == "0" and suite.get("errors") == "0" and suite.get("skipped") == "0"
print("every failing case also fails on HEAD's product code (E8-T12 round 6, published in 3bd0180b):", inherited)
print("every class this round adds or changes passes with no skips:", ok)
sys.exit(0 if inherited and ok else 1)
