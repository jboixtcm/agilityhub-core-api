"""E6-T01: line and branch coverage per touched package, read from target/site/jacoco/jacoco.csv of the last clean verify."""
import csv

PACKAGES = ["clubs.bookings.domain", "clubs.bookings.application", "clubs.bookings.application.ports", "clubs.bookings.api",
            "clubs.followup.domain", "clubs.followup.application", "clubs.followup.api", "clubs.scheduling.application",
            "clubs.scheduling.persistence", "clubs.scheduling.api", "clubs.census.application", "platform.application.jobs"]
totals = {}
with open("target/site/jacoco/jacoco.csv", encoding="utf-8") as source:
    for row in csv.DictReader(source):
        package = row["PACKAGE"].removeprefix("com.agilityhub.core.")
        values = totals.setdefault(package, [0, 0, 0, 0])
        for index, key in enumerate(("LINE_COVERED", "LINE_MISSED", "BRANCH_COVERED", "BRANCH_MISSED")):
            values[index] += int(row[key])
for package in PACKAGES:
    lc, lm, bc, bm = totals[package]
    lines = 100.0 * lc / (lc + lm) if lc + lm else 100.0
    branches = 100.0 * bc / (bc + bm) if bc + bm else 100.0
    print("%-36s lines %5.1f%%  branches %5.1f%%" % (package, lines, branches))
