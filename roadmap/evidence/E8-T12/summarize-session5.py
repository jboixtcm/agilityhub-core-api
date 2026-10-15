"""Totals of the surefire/failsafe reports of the session-5 clean verify, plus the round-5 tests."""
import glob
import xml.etree.ElementTree as ET

for d in ["target/surefire-reports", "target/failsafe-reports"]:
    t = f = e = s = 0
    per = {}
    for p in sorted(glob.glob(d + "/TEST-*.xml")):
        r = ET.parse(p).getroot()
        a = [int(r.get(k, 0)) for k in ("tests", "failures", "errors", "skipped")]
        t += a[0]; f += a[1]; e += a[2]; s += a[3]
        n = r.get("name")
        if n.endswith(("CapturedPaymentInvariantsIT", "PaymentIntentLedgerTest")):
            per[n] = (a, [c.get("name") for c in r.iter("testcase") if "round5" in c.get("name")])
    print(f"{d}: tests {t}, failures {f}, errors {e}, skipped {s}")
    for n, (a, cs) in per.items():
        print(f"  {n}: tests {a[0]}, failures {a[1]}, errors {a[2]}, skipped {a[3]}")
        for c in cs:
            print(f"    passed: {c}")
