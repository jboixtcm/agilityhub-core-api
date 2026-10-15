"""Totals of the surefire/failsafe reports, plus the result of each E8-T12 test of the two payment ledger classes.

E111 point 5: each listed test's status comes from its own <testcase> element (a <failure>, <error> or <skipped> child),
never from the class totals.
"""
import glob
import sys
import xml.etree.ElementTree as ET

CLASSES = ("CapturedPaymentInvariantsIT", "PaymentIntentLedgerTest")
FILTER = sys.argv[1] if len(sys.argv) > 1 else "e8t12_"


def status(case):
    for child in ("failure", "error", "skipped"):
        if case.find(child) is not None:
            return {"failure": "FAILED", "error": "ERROR", "skipped": "SKIPPED"}[child]
    return "passed"


for d in ["target/surefire-reports", "target/failsafe-reports"]:
    t = f = e = s = 0
    per = {}
    for p in sorted(glob.glob(d + "/TEST-*.xml")):
        r = ET.parse(p).getroot()
        a = [int(r.get(k, 0)) for k in ("tests", "failures", "errors", "skipped")]
        t += a[0]; f += a[1]; e += a[2]; s += a[3]
        n = r.get("name")
        if n.endswith(CLASSES):
            per[n] = (a, [(status(c), c.get("name")) for c in r.iter("testcase") if FILTER in c.get("name")])
    print(f"{d}: tests {t}, failures {f}, errors {e}, skipped {s}")
    for n, (a, cs) in per.items():
        print(f"  {n}: tests {a[0]}, failures {a[1]}, errors {a[2]}, skipped {a[3]}")
        for st, c in cs:
            print(f"    {st}: {c}")
