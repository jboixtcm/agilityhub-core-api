"""E9-T06 round 2: the totals of the given test classes (all when none is named), read from target/surefire-reports and
target/failsafe-reports, with one line per test case whose name contains one of the round 2 markers.

Usage: python3 roadmap/evidence/E9-T06/r2_summary.py "<heading>" [Class ...]
"""
import glob
import sys
import xml.etree.ElementTree as ET

MARKERS = ("aClientWhoseQueueFills", "theSendersAreBounded", "twoKeyedFinishes", "twoKeyedJoins", "WriteTimeout", "CaddyAccessLog",
           "everyKeyedBuildSessionWrite")
heading, names = sys.argv[1], set(sys.argv[2:])
print(heading)
for kind in ("surefire", "failsafe"):
    totals = [0, 0, 0, 0]
    classes = 0
    lines = []
    for path in sorted(glob.glob(f"target/{kind}-reports/TEST-*.xml")):
        root = ET.parse(path).getroot()
        name = root.get("name").split(".")[-1]
        if names and name not in names:
            continue
        classes += 1
        counts = [int(root.get(key)) for key in ("tests", "failures", "errors", "skipped")]
        totals = [a + b for a, b in zip(totals, counts)]
        if not names and name not in ("BuildSessionStreamsTest", "CiWorkflowTest", "IdempotencyFilterBuildSessionRoutesTest", "BuildSessionsIT",
                                      "RingSetupsIT", "OpenApiSnapshotTest"):
            continue
        lines.append(f"  {name}: tests={counts[0]}, failures={counts[1]}, errors={counts[2]}, skipped={counts[3]}")
        cases = {}
        for case in root.iter("testcase"):
            label = case.get("name")
            if any(marker in label for marker in MARKERS):
                bad = any(case.find(key) is not None for key in ("failure", "error", "skipped"))
                key = label.split("()")[0].split("[")[0].strip()
                ok, failed = cases.get(key, (0, 0))
                cases[key] = (ok + (0 if bad else 1), failed + (1 if bad else 0))
        for key, (ok, failed) in cases.items():
            lines.append(f"      {key}: passed={ok}, failed={failed}")
    print(f"{kind}: {classes} classes; tests={totals[0]}, failures={totals[1]}, errors={totals[2]}, skipped={totals[3]}")
    print("\n".join(lines))
