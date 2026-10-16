"""E9-T06 counterfactuals: each mutant alone undoes one step, then the tests that must catch it run; every file is restored byte for
byte afterwards (the last line checks that no `MUTANT` marker is left).

Run from the repository root: python3 roadmap/evidence/E9-T06/counterfactuals.py > roadmap/evidence/E9-T06/NN-counterfactuals.log
Optional arguments name the mutants to run (M1 M8).
"""
import sys
import glob
import pathlib
import subprocess
import xml.etree.ElementTree as ET

SRC = "src/main/java/com/agilityhub/core/"
SERVICE = SRC + "courses/application/BuildSessionService.java"
STREAMS = SRC + "courses/api/BuildSessionStreams.java"
MUTANTS = [
    ("M1 step 1: keyed build-session writes stay in the filter's request transaction", SRC + "shared/api/IdempotencyFilter.java",
     "boolean buildSessions = BUILD_SESSIONS.matcher(request.getMethod() + \" \" + path).matches();", "boolean buildSessions = false; // MUTANT",
     "", "BuildSessionsIT"),
    ("M2 step 1: the route group names the start only", SRC + "shared/api/IdempotencyFilter.java",
     "compile(\"POST /api/v1/build-sessions(/join|/[^/]+/finish)?\");", "compile(\"POST /api/v1/build-sessions\"); // MUTANT",
     "IdempotencyFilterBuildSessionRoutesTest", "NoSuchIT"),
    ("M3 step 2: finish gated by the publish switch again", SERVICE,
     "boolean registers = CourseAccess.role(\"ADMIN\") || access.enabled(\"courses.allowInstructorPublish\");",
     "boolean registers = true; if (!CourseAccess.role(\"ADMIN\") && !access.enabled(\"courses.allowInstructorPublish\")) { throw new ApiException(FORBIDDEN); } // MUTANT",
     "", "BuildSessionsIT"),
    ("M4 step 3: a refreshed setup drops its expectedUntil", SRC + "courses/application/RingSetupService.java",
     "instant, actor, current.expectedUntil(), expiresAt, current.status()", "instant, actor, null, expiresAt, current.status() /* MUTANT */",
     "", "BuildSessionsIT"),
    ("M5 step 4: the export counts every marker, not the active ones", SERVICE,
     "long active = ring.geometry().markers().stream().filter(RingMarker::isActive).count();", "long active = ring.geometry().markers().size(); // MUTANT",
     "", "BuildSessionsIT"),
    ("M6 step 4: more than six markers reported as TOO_FEW_MARKERS", SERVICE,
     "var rule = active < BuildSessionExport.MARKERS ? RingGeometryRules.Rule.TOO_FEW_MARKERS : RingGeometryRules.Rule.TOO_MANY_MARKERS;",
     "var rule = RingGeometryRules.Rule.TOO_FEW_MARKERS; // MUTANT", "", "BuildSessionsIT"),
    ("M7 step 4: the export also writes the inactive markers", SRC + "courses/application/BuildSessionExport.java",
     "for (var marker : g.markers().stream().filter(RingMarker::isActive).toList()) {", "for (var marker : g.markers()) { // MUTANT",
     "", "BuildSessionsIT"),
    ("M8 step 5: every send on the caller's thread (the one shared timer) again", STREAMS,
     "try { senders.execute(this::drain); }", "try { drain(); /* MUTANT */ }", "BuildSessionStreamsTest", "NoSuchIT"),
    ("M9 step 5: no write timeout", STREAMS,
     "        topics.values().forEach(set -> List.copyOf(set).forEach(subscriber -> subscriber.watch(now)));\n", "        // MUTANT\n",
     "BuildSessionStreamsTest", "NoSuchIT"),
    ("M10 step 5: an unbounded queue", STREAMS,
     "            if (queue.size() >= QUEUE) { drop(\"queue full\"); return; }\n", "            // MUTANT\n", "BuildSessionStreamsTest", "NoSuchIT"),
    ("M11 step 6: a stream lives 30 minutes whatever its token", STREAMS,
     "if (expiresAt == null) { return TIMEOUT; }", "if (true) { return TIMEOUT; } // MUTANT", "BuildSessionStreamsTest", "BuildSessionsIT"),
    ("M12 step 7: an outlived session's streams wait for P9", STREAMS,
     "over = current.isPresent() && sessions.over(current.get());", "over = false; // MUTANT", "BuildSessionStreamsTest", "BuildSessionsIT"),
    ("M13 step 8: finish's contract leaves out RING_WITHOUT_GEOMETRY", SRC + "courses/api/BuildSessionsController.java",
     "COURSE_NOT_VISIBLE,\n            RING_WITHOUT_GEOMETRY, STALE_VERSION})", "COURSE_NOT_VISIBLE,\n            STALE_VERSION}) // MUTANT",
     "", "OpenApiSnapshotTest"),
    ("M14 step 9: the query token on every method of the stream's path", SRC + "configuration/SecurityConfiguration.java",
     "return \"GET\".equals(request.getMethod()) && BUILD_SESSION_STREAM", "return /* MUTANT */ BUILD_SESSION_STREAM", "", "BuildSessionsIT"),
    ("M15 step 9: the query token on every route", SRC + "configuration/SecurityConfiguration.java",
     ": buildSessionStream(request) ? streamBearers.resolve(request) : bearers.resolve(request))", ": streamBearers.resolve(request)) /* MUTANT */",
     "", "BuildSessionsIT"),
    ("M16 step 9: the trace filter puts the query string in the MDC", SRC + "shared/api/RequestTraceFilter.java",
     "        MDC.put(\"accountId\", \"-\");\n", "        MDC.put(\"accountId\", \"-\"); MDC.put(\"query\", String.valueOf(request.getQueryString())); // MUTANT\n",
     "RequestTraceFilterTest", "NoSuchIT"),
    ("M17 step 9: the trace filter logs the query token", SRC + "shared/api/RequestTraceFilter.java",
     "        MDC.put(\"accountId\", \"-\");\n",
     "        MDC.put(\"accountId\", \"-\"); org.slf4j.LoggerFactory.getLogger(RequestTraceFilter.class).info(\"request {}\", request.getParameter(\"access_token\")); // MUTANT\n",
     "", "BuildSessionsIT"),
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
