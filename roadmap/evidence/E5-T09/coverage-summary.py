"""E5-T09: line/branch coverage of the packages this task touched, from target/site/jacoco/jacoco.csv (after clean verify)."""
import csv
from collections import defaultdict

PACKAGES = ["platform.application.jobs", "clubs.catalogs.application", "clubs.training.application", "clubs.scheduling.application",
            "clubs.bookings.application", "clubs.common.application", "clubs.census.application", "shared.application",
            "clubs.catalogs.api", "clubs.training.api"]
totals = defaultdict(lambda: [0, 0, 0, 0])
with open("target/site/jacoco/jacoco.csv", newline="") as source:
    for row in csv.DictReader(source):
        package = row["PACKAGE"].removeprefix("com.agilityhub.core.")
        if package in PACKAGES:
            t = totals[package]
            t[0] += int(row["LINE_MISSED"]); t[1] += int(row["LINE_COVERED"]); t[2] += int(row["BRANCH_MISSED"]); t[3] += int(row["BRANCH_COVERED"])
for package in PACKAGES:
    lm, lc, bm, bc = totals[package]
    lines = lc / (lm + lc) if lm + lc else 1.0
    branches = bc / (bm + bc) if bm + bc else 1.0
    print(f"{package:32s} lines {lines:6.1%}  branches {branches:6.1%}")
