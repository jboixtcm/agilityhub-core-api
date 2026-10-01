#!/usr/bin/env python3
"""E7-T04 round 3, point 1: NotificationEngineIT (T-11-31) and OutboxIT ten times in a row, each in a fresh Maven run on two
processors like the CI runner (-XX:ActiveProcessorCount=2, the E5-T07 precedent).

    python3 roadmap/evidence/E7-T04/r3-repeat-engine-it.py

Each run's complete output goes to roadmap/evidence/E7-T04/33-r3-repeat-NN.log; this script prints, per run, the exit code,
the processors the JVM saw, the two classes' test counts and T-11-31's line (the conflicts and where they were), and exits
non-zero when any run failed.
"""
import pathlib
import re
import subprocess
import sys

RUNS = 10
COMMAND = ["./mvnw", "-Dtest=NoSuchUnitTest", "-Dsurefire.failIfNoSpecifiedTests=false", "-Dit.test=NotificationEngineIT,OutboxIT",
           "-Dfailsafe.failIfNoSpecifiedTests=false", "-Djacoco.skip=true", "-DargLine=-XX:ActiveProcessorCount=2", "verify"]
EVIDENCE = pathlib.Path("roadmap/evidence/E7-T04")

print("command (each run): " + " ".join(COMMAND))
failed = 0
for run in range(1, RUNS + 1):
    log = EVIDENCE / f"33-r3-repeat-{run:02d}.log"
    with log.open("w") as out:
        code = subprocess.run(COMMAND, stdout=out, stderr=subprocess.STDOUT).returncode
    text = log.read_text(errors="replace")
    processors = re.findall(r"Integration JVM availableProcessors=\d+", text)
    classes = re.findall(r"Tests run: \d+, Failures: \d+, Errors: \d+, Skipped: \d+, Time elapsed: [\d.]+ s(?: <<< FAILURE!)? -- in \S+", text)
    t1131 = re.findall(r"T-11-31 200 concurrent ReminderDue deliveries.*", text)
    build = "BUILD SUCCESS" if "BUILD SUCCESS" in text else "BUILD FAILURE"
    print(f"run {run:02d}: exit {code} · {build} · {processors[0] if processors else 'availableProcessors not printed'} · {log.name}")
    for line in classes:
        print("  " + line.split(" -- in com.agilityhub.core.")[0] + " -- " + line.rsplit(".", 1)[-1])
    for line in t1131:
        print("  " + line)
    if code != 0 or build != "BUILD SUCCESS":
        failed += 1
print(f"{RUNS - failed} of {RUNS} runs green")
sys.exit(1 if failed else 0)
