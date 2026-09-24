"""Round 2 and 3 test summary: totals of surefire and failsafe plus the classes this round changes (AGENTS.md rule 4)."""
import glob
import xml.etree.ElementTree as ET

CLASSES = ("com.agilityhub.core.migration.PlayoffAdapterTest", "com.agilityhub.core.migration.PlayoffMigrationIT")
out = []
for kind in ("surefire", "failsafe"):
    totals = [0, 0, 0, 0]
    per = {}
    for path in glob.glob(f"target/{kind}-reports/TEST-*.xml"):
        root = ET.parse(path).getroot()
        values = [int(root.get(k, 0)) for k in ("tests", "failures", "errors", "skipped")]
        totals = [a + b for a, b in zip(totals, values)]
        per[root.get("name")] = values
    out.append(f"{kind}: tests={totals[0]} failures={totals[1]} errors={totals[2]} skipped={totals[3]}")
    for name in CLASSES:
        if name in per:
            out.append(f"  {name} " + "/".join(map(str, per[name])))
out.append("(tests/failures/errors/skipped, read from target/surefire-reports and target/failsafe-reports after ./mvnw -q clean verify, exit 0)")
print("\n".join(out))
