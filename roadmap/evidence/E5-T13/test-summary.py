"""Round 2 test summary: surefire/failsafe totals, the class this round changes and its T-15-25 methods, and the
payments.application coverage (AGENTS.md session protocol step 4). Run after ./mvnw -q clean verify."""
import csv
import glob
import xml.etree.ElementTree as ET

CLASS = "com.agilityhub.core.clubs.bookings.api.SingleClassCheckoutIT"
out = []
for kind in ("surefire", "failsafe"):
    totals, detail = [0, 0, 0, 0], []
    for path in glob.glob(f"target/{kind}-reports/TEST-*.xml"):
        root = ET.parse(path).getroot()
        values = [int(root.get(k, 0)) for k in ("tests", "failures", "errors", "skipped")]
        totals = [a + b for a, b in zip(totals, values)]
        if root.get("name") == CLASS:
            detail.append(f"  SingleClassCheckoutIT tests={values[0]} failures={values[1]} errors={values[2]} skipped={values[3]}")
            for case in root.iter("testcase"):
                if case.get("name").startswith("T_15_25"):
                    failed = case.find("failure") is not None or case.find("error") is not None
                    detail.append(f"    {case.get('name')} {'FAILED' if failed else 'passed'}")
    out.append(f"{kind} TOTAL tests={totals[0]} failures={totals[1]} errors={totals[2]} skipped={totals[3]}")
    out.extend(detail)
covered = {"LINE": [0, 0], "BRANCH": [0, 0]}
LABELS = {"LINE": "lines", "BRANCH": "branches"}
for row in csv.DictReader(open("target/site/jacoco/jacoco.csv")):
    if row["PACKAGE"] == "com.agilityhub.core.payments.application":
        for kind in covered:
            covered[kind][0] += int(row[f"{kind}_COVERED"]); covered[kind][1] += int(row[f"{kind}_MISSED"])
out.append("payments.application " + " ".join(f"{LABELS[k]} {100 * c / (c + m):.1f} %" for k, (c, m) in covered.items()))
print("\n".join(out))
