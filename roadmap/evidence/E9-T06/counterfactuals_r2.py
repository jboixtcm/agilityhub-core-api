"""E9-T06 round 2 counterfactuals: each mutant alone undoes one point of round 2, then the tests that must catch it run; every file
is restored byte for byte afterwards (the last line checks that no `MUTANT` marker is left).

Run from the repository root: python3 roadmap/evidence/E9-T06/counterfactuals_r2.py > roadmap/evidence/E9-T06/NN-counterfactuals-r2.log
Optional arguments name the mutants to run (R1a R2).
"""
import glob
import pathlib
import subprocess
import sys
import xml.etree.ElementTree as ET

SRC = "src/main/java/com/agilityhub/core/"
SERVICE = SRC + "courses/application/BuildSessionService.java"
STREAMS = SRC + "courses/api/BuildSessionStreams.java"
MUTANTS = [
    ("R1a point 1: an unbounded queue (the queue test must still catch it)", STREAMS,
     "            if (queue.size() >= QUEUE) { drop(\"queue full\"); return; }\n", "            // MUTANT\n", "BuildSessionStreamsTest", "NoSuchIT"),
    ("R1b point 1: a queue one frame shorter (the boundary)", STREAMS,
     "            if (queue.size() >= QUEUE) { drop(\"queue full\"); return; }\n", "            if (queue.size() >= QUEUE - 1) { drop(\"queue full\"); return; } // MUTANT\n",
     "BuildSessionStreamsTest", "NoSuchIT"),
    ("R2 point 2: finish turns the setup's write conflict into STALE_VERSION again", SERVICE,
     "String setupId = registers ? setups.built(placement, course, actor) : null;",
     "String setupId = registers ? WriteConflicts.guard(() -> setups.built(placement, course, actor)) : null; // MUTANT", "", "BuildSessionsIT"),
    ("R3 point 3: keyed build-session writes stay in the filter's request transaction (E9-T06's M1)", SRC + "shared/api/IdempotencyFilter.java",
     "boolean buildSessions = BUILD_SESSIONS.matcher(request.getMethod() + \" \" + path).matches();", "boolean buildSessions = false; // MUTANT",
     "", "BuildSessionsIT"),
    ("R4 point 4: CI no longer runs the Caddy access-log tests", ".github/workflows/ci.yml",
     "      - name: Caddy access log drops the stream's query token (E9-T06)\n        run: python3 deploy/test_access_log.py -v\n", "      # MUTANT\n",
     "CiWorkflowTest", "NoSuchIT"),
    ("R5a point 5: an unbounded sender pool", STREAMS,
     "new ThreadPoolExecutor(0, senders, 60, TimeUnit.SECONDS,", "new ThreadPoolExecutor(0, Integer.MAX_VALUE /* MUTANT */, 60, TimeUnit.SECONDS,",
     "BuildSessionStreamsTest", "NoSuchIT"),
    ("R5b point 5: Tomcat's default write timeout", "src/main/resources/application.yml",
     "    connection-timeout: 20s\n", "    # MUTANT\n", "", "BuildSessionsIT"),
    ("R6 point 6: join declares no Idempotency-Key", SRC + "courses/api/BuildSessionsController.java",
     "public BuildSession joinBuildSession(@Valid @RequestBody BuildJoinRequest request,\n            @RequestHeader(value = \"Idempotency-Key\", required = false) String idempotencyKey) {",
     "public BuildSession joinBuildSession(@Valid @RequestBody BuildJoinRequest request) { // MUTANT", "", "OpenApiSnapshotTest"),
]


def failures():
    found = []
    for path in sorted(glob.glob("target/surefire-reports/TEST-*.xml") + glob.glob("target/failsafe-reports/TEST-*.xml")):
        root = ET.parse(path).getroot()
        for case in root.iter("testcase"):
            for kind in ("failure", "error"):
                problem = case.find(kind)
                if problem is not None:
                    message = (problem.get("message") or "").strip().splitlines()
                    found.append(f"{case.get('classname').split('.')[-1]}.{case.get('name')} [{kind}] {message[0][:220] if message else ''}")
    return found


for label, file, old, new, unit, it in MUTANTS:
    if len(sys.argv) > 1 and label.split()[0] not in sys.argv[1:]:
        continue
    path = pathlib.Path(file)
    original = path.read_bytes()
    text = original.decode()
    assert text.count(old) == 1, (label, "anchor not found once")
    try:
        path.write_text(text.replace(old, new))
        for report in glob.glob("target/surefire-reports/*") + glob.glob("target/failsafe-reports/*"):
            pathlib.Path(report).unlink()
        command = ["./mvnw", "-q", "verify", "-Djacoco.skip=true", "-Dsurefire.failIfNoSpecifiedTests=false", "-Dfailsafe.failIfNoSpecifiedTests=false",
                   "-Dmaven.test.failure.ignore=true", "-Dtest=" + (unit or "NoSuchUnitTest"), "-Dit.test=" + it]
        run = subprocess.run(command, capture_output=True, text=True)
        print(f"== {label}\n   file: {file}\n   command: {' '.join(command)}\n   exit: {run.returncode}", flush=True)
        lines = failures()
        if not lines and run.returncode != 0:
            lines = [line for line in run.stdout.splitlines() if "ERROR" in line][:5]
        for line in lines:
            print("   FAILED " + line, flush=True)
        if not lines:
            print("   (no test failed)", flush=True)
    finally:
        path.write_bytes(original)
print("All mutants reverted:", all(b"MUTANT" not in pathlib.Path(m[1]).read_bytes() for m in MUTANTS))
