#!/usr/bin/env python3
"""E7-T07: totals of target/surefire-reports and target/failsafe-reports after `./mvnw -q clean verify`, one line per test
class the task adds or changes (tests, failures, errors, skipped), and the JaCoCo coverage of the packages it touches."""
import csv
from pathlib import Path
import xml.etree.ElementTree as ElementTree

ROOT = Path(__file__).resolve().parents[3]
CLASSES = [
    "com.agilityhub.core.clubs.messaging.api.AnnouncementsIT",
    "com.agilityhub.core.clubs.bookings.api.NotificationActionsIT",
    "com.agilityhub.core.platform.application.audit.AuditWriterTest",
    "com.agilityhub.core.platform.application.audit.AuditedAspectTest",
    # Unchanged, but they read what this task touches: the audit contract and API, the matrix and the demo seed.
    "com.agilityhub.core.platform.application.audit.AuditContractTest",
    "com.agilityhub.core.platform.application.audit.AuditedAspectIT",
    "com.agilityhub.core.platform.api.AuditQueriesIT",
    "com.agilityhub.core.clubs.messaging.application.engine.NotificationMatrixTest",
    "com.agilityhub.core.clubs.census.application.DemoScenarioSeedIT",
    "com.agilityhub.core.configuration.OpenApiSnapshotTest",
    "com.agilityhub.core.arch.ArchitectureTest",
]
# The build's PACKAGE rule asks domain/application ≥ 85 % lines and ≥ 80 % branches, api ≥ 70 % lines.
PACKAGES = [
    "com.agilityhub.core.platform.application.audit",
    "com.agilityhub.core.clubs.messaging.application",
    "com.agilityhub.core.clubs.messaging.api",
]


def coverage():
    path = ROOT / "target" / "site" / "jacoco" / "jacoco.csv"
    if not path.exists():
        print("jacoco.csv: missing")
        return
    sums = {}
    with path.open() as handle:
        for row in csv.DictReader(handle):
            entry = sums.setdefault(row["PACKAGE"], [0, 0, 0, 0])
            entry[0] += int(row["LINE_COVERED"]); entry[1] += int(row["LINE_MISSED"])
            entry[2] += int(row["BRANCH_COVERED"]); entry[3] += int(row["BRANCH_MISSED"])
    for name in PACKAGES:
        lc, lm, bc, bm = sums.get(name, [0, 0, 0, 0])
        lines = 100.0 * lc / (lc + lm) if lc + lm else 100.0
        branches = 100.0 * bc / (bc + bm) if bc + bm else 100.0
        print(f"  coverage {name}: lines {lines:.1f} % ({lc}/{lc + lm}), branches {branches:.1f} % ({bc}/{bc + bm})")


def suites(folder):
    for path in sorted((ROOT / "target" / folder).glob("TEST-*.xml")):
        root = ElementTree.parse(path).getroot()
        yield root.get("name"), {k: int(root.get(k, "0")) for k in ("tests", "failures", "errors", "skipped")}


def main():
    seen = {}
    for folder in ("surefire-reports", "failsafe-reports"):
        total = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
        classes = 0
        for name, counts in suites(folder):
            classes += 1
            for key in total:
                total[key] += counts[key]
            seen[name] = (folder, counts)
        print(f"{folder}: {classes} classes, tests={total['tests']} failures={total['failures']} errors={total['errors']} skipped={total['skipped']}")
    for name in CLASSES:
        folder, counts = seen.get(name, ("missing", None))
        print(f"  {name.rsplit('.', 1)[1]} ({folder}): " + ("NOT RUN" if counts is None else
              f"tests={counts['tests']} failures={counts['failures']} errors={counts['errors']} skipped={counts['skipped']}"))
    coverage()


if __name__ == "__main__":
    main()
