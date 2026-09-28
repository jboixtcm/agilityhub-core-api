"""E6-T04 round 2: line and branch coverage of the packages the task touches, from target/site/jacoco/jacoco.csv of the last
./mvnw clean verify (the verify's own JaCoCo check enforces the thresholds; this prints the numbers for the report)."""
import csv
from collections import defaultdict

PACKAGES = ["com.agilityhub.core.clubs.bookings.application", "com.agilityhub.core.clubs.bookings.application.jobs",
            "com.agilityhub.core.clubs.bookings.persistence", "com.agilityhub.core.clubs.scheduling.application",
            "com.agilityhub.core.clubs.census.application"]
totals = defaultdict(lambda: [0, 0, 0, 0])
with open("target/site/jacoco/jacoco.csv", newline="") as source:
    for row in csv.DictReader(source):
        t = totals[row["PACKAGE"]]
        t[0] += int(row["LINE_MISSED"]); t[1] += int(row["LINE_COVERED"]); t[2] += int(row["BRANCH_MISSED"]); t[3] += int(row["BRANCH_COVERED"])
for package in PACKAGES:
    lm, lc, bm, bc = totals[package]
    lines = 100.0 * lc / (lm + lc) if lm + lc else 100.0
    branches = 100.0 * bc / (bm + bc) if bm + bc else 100.0
    print("%-60s lines %.1f%%  branches %.1f%%" % (package, lines, branches))
