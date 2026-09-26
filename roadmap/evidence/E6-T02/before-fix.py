"""E6-T02 before-fix run: copies the working tree to target/before-fix (the working tree is never touched), reverts only the
five changes this task made to pre-existing production code, and runs the tests that pin them. Each must fail there."""
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
TREE = ROOT / "target/before-fix"
MAIN = "src/main/java/com/agilityhub/core/"
REVERTS = [
    # 1. R-08-19: the N-05 filter for an ADMIN's «ha avisat» (origin INSTRUCTOR, by ADMIN).
    (MAIN + "clubs/bookings/application/BookingNotifications.java",
     r'case "N-05" -> !created && \(Set\.of\("MEMBER", "INSTRUCTOR"\)\.contains\(by\) \|\| "INSTRUCTOR"\.equals\(origin\)\) && !"BACKOFFICE"\.equals\(origin\)\s*&& !"PAYMENT_TIMEOUT"',
     'case "N-05" -> !created && Set.of("MEMBER", "INSTRUCTOR").contains(by) && !"BACKOFFICE".equals(origin) && !"PAYMENT_TIMEOUT"'),
    # 2. R-10-00: the handler's name in the staff training cells.
    (MAIN + "clubs/training/application/TrainingOccupancyService.java",
     r"handlers\.getOrDefault\(b\.dogId\(\), names\.get\(b\.memberId\(\)\)\)", "names.get(b.memberId())"),
    # 3. R-10-04: the keyed PUT of the attendance save in the idempotency filter.
    (MAIN + "shared/api/IdempotencyFilter.java",
     r'boolean keyedMethod = "POST"\.equals\(request\.getMethod\(\)\)\s*\|\| "PUT"\.equals\(request\.getMethod\(\)\) && KEYED_PUT\.matcher\(request\.getRequestURI\(\)\.substring\(request\.getContextPath\(\)\.length\(\)\)\)\.matches\(\);',
     'boolean keyedMethod = "POST".equals(request.getMethod());'),
    # 4. S10 §5: PRESENT/NO_SHOW → NOTIFIED skips S08's own attendance guard.
    (MAIN + "clubs/bookings/application/BookingCancellationService.java",
     r"\|\| !attendanceNotice && attendance\.marked\(b\.id\(\)\)", "|| attendance.marked(b.id())"),
    # 5. R-10-14: S07's history rows carry the start instant.
    (MAIN + "clubs/activities/application/ActivityQueryService.java", r'"state",state,"startsAt",start,', '"state",state,'),
]
TESTS = ("AttendanceIT#T_10_12*+T_10_01_anAdmin*+R_08_10*,InstructorAggregatesIT#T_10_20_theWeek*+T_10_19*")

shutil.rmtree(TREE, ignore_errors=True)
TREE.mkdir(parents=True)
for name in ("src", ".mvn", "docs", "seeds"):
    shutil.copytree(ROOT / name, TREE / name)
for name in ("pom.xml", "mvnw"):
    shutil.copy2(ROOT / name, TREE / name)
for path, pattern, replacement in REVERTS:
    file = TREE / path
    text, count = re.subn(pattern, replacement, file.read_text(encoding="utf-8"))
    if count != 1:
        sys.exit(f"revert did not apply exactly once: {path} ({count})")
    file.write_text(text, encoding="utf-8")
    print(f"reverted in the copy: {path}")
command = ["./mvnw", "-q", "verify", "-Dtest=NONE", "-Dsurefire.failIfNoSpecifiedTests=false", "-Dit.test=" + TESTS, "-Djacoco.skip=true"]
print("$ (cd target/before-fix && " + " ".join(command) + ")", flush=True)
result = subprocess.run(command, cwd=TREE, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
print(f"exit {result.returncode}")
for line in result.stdout.splitlines():
    if re.search(r"Tests run:|<<< (FAILURE|ERROR)|^\[ERROR\]   [A-Za-z]", line) or re.match(r"^(expected|but was|Expecting|org\.opentest4j|java\.lang\.AssertionError)", line.strip()):
        print(line[:400])
