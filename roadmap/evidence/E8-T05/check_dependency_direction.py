"""Read-only evidence for E8-T05's conflicting dependency requirements."""
from pathlib import Path


def excerpt(path, first, last):
    lines = Path(path).read_text().splitlines()
    for number in range(first, last + 1):
        print(f"{path}:{number}: {lines[number - 1]}")


def matching(path, fragment):
    for number, line in enumerate(Path(path).read_text().splitlines(), 1):
        if fragment in line:
            excerpt(path, number, number)
            return


task = "roadmap/tasks/E8-T05.md"
rules = "src/test/java/com/agilityhub/core/arch/ArchitectureRules.java"
tests = "src/test/java/com/agilityhub/core/arch/ArchitectureTest.java"
matching(task, "- Context direction (ArchUnit):")
excerpt(rules, 224, 233)
matching(rules, "}).should().beFreeOfCycles();")
matching(tests, "E0_T01_contextsHaveNoCycles")
matching(tests, "E8_T01_censusReachesPaymentsAndBookingsOnlyThroughPorts")
for source in (
    "clubs/bookings/application/BookingChecks.java",
    "clubs/training/application/TrainingBookingService.java",
    "clubs/activities/application/ActivityContext.java",
):
    matching("src/main/java/com/agilityhub/core/" + source,
             "import com.agilityhub.core.clubs.census.application.")
print("Static finding: the requested census -> bookings/training/activities calls add reverse context edges.")
print("Static finding: census implementing a payments/bookings port is also forbidden by CENSUS_THROUGH_PORTS.")
print("No product code changed; no test failure is claimed. Organizer ruling needed on the required dependency direction.")
