"""E9-T04 counterfactuals: each mutant alone, then the tests that must catch it; every file is restored byte for byte afterwards.

Run from the repository root: python3 roadmap/evidence/E9-T04/counterfactuals.py > roadmap/evidence/E9-T04/05-counterfactuals.log
Optional arguments name the mutants to run (M3 M8); `-Dmaven.test.failure.ignore=true` lets the ITs run after a unit test failed
(run 05 lacked it, so M3 and M8 stopped at their unit test; run 06 repeats them).
"""
import sys
import glob
import pathlib
import subprocess
import xml.etree.ElementTree as ET

SRC = "src/main/java/com/agilityhub/core/"
MUTANTS = [
    ("M1 streams never refresh (no event after a change)", SRC + "courses/api/BuildSessionStreams.java",
     "    void refresh(Key key) {\n", "    void refresh(Key key) { if (true) { return; } // MUTANT\n", "", "BuildSessionsIT"),
    ("M2 no query-token resolver on the stream", SRC + "configuration/SecurityConfiguration.java",
     ": buildSessionStream(request) ? streamBearers.resolve(request) : bearers.resolve(request))", ": bearers.resolve(request)) /* MUTANT */", "", "BuildSessionsIT"),
    ("M3 the async dispatch is checked again", SRC + "platform/api/RequiresModuleInterceptor.java",
     "        if (request.getDispatcherType() == jakarta.servlet.DispatcherType.ASYNC) { return true; }\n", "        // MUTANT\n",
     "RequiresModuleInterceptorTest", "BuildSessionsIT"),
    ("M4 finish registers no setup", SRC + "courses/application/BuildSessionService.java",
     "String setupId = setups.built(placement, course, actor);", "String setupId = null; // MUTANT", "", "BuildSessionsIT"),
    ("M5 an outlived session still takes writes", SRC + "courses/application/BuildSessionService.java",
     "\n                || BuildSessionRules.outlived(session.status(), session.startedAt(), session.createdAt(), maxHours(), now)) {", ") { // MUTANT",
     "", "BuildSessionsIT"),
    ("M6 P9 plans no abandonment", SRC + "clubs/common/application/CleanupJob.java",
     "            buildSessions.outlived(now).forEach(id -> items.add(new JobItem(\"BuildSession\", id, ABANDON)));\n", "            // MUTANT\n",
     "", "BuildSessionsIT"),
    ("M7 no six-marker check before the export", SRC + "courses/application/BuildSessionService.java",
     "if (ring.geometry().markers().size() != BuildSessionExport.MARKERS) {", "if (false) { // MUTANT", "", "BuildSessionsIT"),
    ("M8 a failed export self-check is a 400 again (step 2 undone)", SRC + "courses/application/CourseSchemas.java",
     "throw new ApiException(ErrorCode.INTERNAL_ERROR, new IllegalStateException(\"BuildSessionExportV1 self-check failed: \" + errors));",
     "throw new ApiException(ErrorCode.COURSE_MODEL_INVALID, Map.of(\"schemaErrors\", errors)); // MUTANT", "CourseSchemasTest", "BuildSessionsIT"),
    ("M9 a QR join is recorded as CODE", SRC + "courses/application/BuildSessionService.java",
     "byCode.isPresent() ? JoinedVia.CODE : JoinedVia.QR", "JoinedVia.CODE /* MUTANT */", "", "BuildSessionsIT"),
    ("M10 finish never refreshes the active setup of its placement", SRC + "courses/application/RingSetupService.java",
     "if (current != null && placement.id().equals(current.placementId())) {", "if (false) { // MUTANT", "", "BuildSessionsIT"),
    ("M11 finish ignores courses.allowInstructorPublish", SRC + "courses/application/BuildSessionService.java",
     "        if (!CourseAccess.role(\"ADMIN\") && !access.enabled(\"courses.allowInstructorPublish\")) { throw new ApiException(FORBIDDEN); }\n"
     "        var now = writable(session);\n        var placement = placement(session.placementId());",
     "        var now = writable(session); // MUTANT\n        var placement = placement(session.placementId());", "", "BuildSessionsIT"),
    # Run 13, after the organizer's E117 note and the concurrent-taps fix (M13's first replacement did not compile; run 14 repeats it).
    ("M12 a dismantle racing another write is unguarded (E117)", SRC + "courses/application/RingSetupService.java",
     "var ended = WriteConflicts.guard(() -> end(setup, SetupStatus.DISMANTLED, now, actor, DomainEvent.Origin.BACKOFFICE));",
     "var ended = end(setup, SetupStatus.DISMANTLED, now, actor, DomainEvent.Origin.BACKOFFICE); // MUTANT", "", "RingSetupsIT"),
    ("M13 a renewal racing another write is unguarded (E117)", SRC + "courses/application/RingSetupService.java",
     "if (!WriteConflicts.guard(() -> setups.replaceActive(renewed))) {", "if (!setups.replaceActive(renewed)) { /* MUTANT */", "", "RingSetupsIT"),
    ("M14 build-session writes are not retried on a conflict", SRC + "courses/application/BuildSessionService.java",
     "        for (int attempt = 1; ; attempt++) {\n", "        for (int attempt = ATTEMPTS; ; attempt++) { // MUTANT\n", "", "BuildSessionsIT"),
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
        print(f"== {label}\n   file: {file}\n   command: {' '.join(command)}\n   exit: {run.returncode}")
        lines = failures()
        if not lines and run.returncode != 0:
            lines = [line for line in run.stdout.splitlines() if "ERROR" in line][:5]
        for line in lines:
            print("   FAILED " + line)
        if not lines:
            print("   (no test failed)")
    finally:
        path.write_bytes(original)
print("All mutants reverted:", all(b"MUTANT" not in pathlib.Path(m[1]).read_bytes() for m in MUTANTS))
