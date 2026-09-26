"""E6-T02: line and branch coverage per touched package, read from target/site/jacoco/jacoco.csv of the last clean verify."""
import csv

PACKAGES = ["clubs.bookings.domain", "clubs.bookings.application", "clubs.bookings.application.ports", "clubs.bookings.api",
            "clubs.bookings.persistence", "clubs.scheduling.application", "clubs.census.application", "clubs.catalogs.application",
            "clubs.training.application", "clubs.activities.application", "shared.api"]
CLASSES = ["AttendanceSheetService", "AttendanceSheetQuery", "InstructorDayQuery", "WeekAgendaQuery", "WeekAgendaPdf", "InstructorCardQuery", "HistoryQuery",
           "AttendanceListQuery", "AttendanceConsumers", "AttendanceStates", "AttendanceCallers", "NoShowNoticeClaims", "ClaimNoShowCommand",
           "AttendanceTransitions", "NoticeRules", "AttendanceMetrics", "HistoryRules", "AttendanceWindow", "InstructorScheduleAccess",
           "AttendanceCensusAccess", "TrainingHistory", "ActivityHistory", "InstructorController", "BookingCancellationService"]
totals = {}
classes = {}
with open("target/site/jacoco/jacoco.csv", encoding="utf-8") as source:
    for row in csv.DictReader(source):
        package = row["PACKAGE"].removeprefix("com.agilityhub.core.")
        counts = [int(row[key]) for key in ("LINE_COVERED", "LINE_MISSED", "BRANCH_COVERED", "BRANCH_MISSED")]
        values = totals.setdefault(package, [0, 0, 0, 0])
        for index in range(4):
            values[index] += counts[index]
        name = row["CLASS"].split(".")[0]
        if name in CLASSES:
            aggregate = classes.setdefault(name, [0, 0, 0, 0])
            for index in range(4):
                aggregate[index] += counts[index]

def pct(covered, missed):
    return 100.0 * covered / (covered + missed) if covered + missed else 100.0

for package in PACKAGES:
    lc, lm, bc, bm = totals[package]
    print("%-36s lines %5.1f%%  branches %5.1f%%" % (package, pct(lc, lm), pct(bc, bm)))
print("New or changed classes:")
for name in CLASSES:
    lc, lm, bc, bm = classes.get(name, [0, 0, 0, 0])
    print("  %-30s lines %5.1f%% (%d missed)  branches %5.1f%% (%d missed)" % (name, pct(lc, lm), lm, pct(bc, bm), bm))
