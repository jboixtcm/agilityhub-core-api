"""E6-T02 round 2, item 3: T-10-03 now drives the real save path, where nothing was wrong, so there is no fix to revert.
This run shows the table test is sensitive to S08's boundaries instead: in a copy of the working tree
(target/mutation-t1003; the working tree is never touched) each mutation below is applied alone and T-10-03 is run.
Each run must fail on the row the mutation moves."""
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
POLICY = "src/main/java/com/agilityhub/core/clubs/bookings/domain/CancellationPolicy.java"
MUTATIONS = [
    ("the threshold itself becomes late (> → ≥)", r"boolean late = now\.isAfter\(inTimeUntil\(classStartsAt, lateThresholdMinutes\)\);",
     "boolean late = !now.isBefore(inTimeUntil(classStartsAt, lateThresholdMinutes));"),
    ("the waiting list is told at exactly 30 minutes (> → ≥)", r"\.compareTo\(Duration\.ofMinutes\(notifyThresholdMinutes\)\) > 0;",
     ".compareTo(Duration.ofMinutes(notifyThresholdMinutes)) >= 0;"),
]
for index, (label, pattern, replacement) in enumerate(MUTATIONS, start=1):
    tree = ROOT / "target/mutation-t1003"
    shutil.rmtree(tree, ignore_errors=True)
    tree.mkdir(parents=True)
    for name in ("src", ".mvn", "docs", "seeds"):
        shutil.copytree(ROOT / name, tree / name)
    for name in ("pom.xml", "mvnw"):
        shutil.copy2(ROOT / name, tree / name)
    file = tree / POLICY
    text, count = re.subn(pattern, replacement, file.read_text(encoding="utf-8"))
    if count != 1:
        sys.exit(f"mutation {index} did not apply exactly once ({count})")
    file.write_text(text, encoding="utf-8")
    print(f"mutation {index}: {label}", flush=True)
    command = ["./mvnw", "-q", "verify", "-Dtest=NONE", "-Dsurefire.failIfNoSpecifiedTests=false", "-Dit.test=AttendanceIT#T_10_03*", "-Djacoco.skip=true"]
    print("$ (cd target/mutation-t1003 && " + " ".join(command) + ")", flush=True)
    result = subprocess.run(command, cwd=tree, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    print(f"exit {result.returncode}")
    for line in result.stdout.splitlines():
        if re.search(r"^T-10-03 |Tests run:.*AttendanceIT|<<< FAILURE", line) or re.match(r"^(expected|but was|\[saved at)", line.strip()):
            print("  " + line[:300].rstrip())
