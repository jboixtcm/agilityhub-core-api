"""E9-T05 counterfactuals: each fix undone alone, then the tests that must catch it; every file is restored byte for byte afterwards.

Run from the repository root: python3 roadmap/evidence/E9-T05/counterfactuals.py > roadmap/evidence/E9-T05/NN-counterfactuals.log
Optional arguments name the mutants to run (M3 M8). `-Dmaven.test.failure.ignore=true` lets the ITs run after a unit test failed.
"""
import glob
import pathlib
import subprocess
import sys
import xml.etree.ElementTree as ET

SRC = "src/main/java/com/agilityhub/core/"
MUTANTS = [
    ("M1 (E117 point 1) a ring change keeps the hidden placement with COURSES off", SRC + "clubs/scheduling/application/ClassPlacements.java",
     "return context.config().modules().contains(Module.COURSES) ? placementId : null;", "return placementId; // MUTANT", "", "PlanningIT"),
    ("M2 (E117 point 2) N-31's level without the AgilityHub fallback", SRC + "clubs/training/application/RingSetupNotificationFacts.java",
     'return !names.isEmpty() ? names : setup.agilityhubLevel() == null ? "" : setup.agilityhubLevel();', 'return names; // MUTANT', "",
     "TemplateVariableParityIT"),
    ("M3 (E117 point 3) no plannedPlacement in the member's class", SRC + "clubs/scheduling/application/SessionProjection.java",
     'if(enabled(Module.COURSES) && Boolean.TRUE.equals(context.config().get("courses.showSetupToMembers",Boolean.class))) {',
     "if(false) { // MUTANT", "", "PlanningIT"),
    ("M4 (E117 point 4) a setup from a placement whose course was deleted", SRC + "courses/application/RingSetupService.java",
     "        if (placement != null) { access.course(placement.courseId(), false); }\n", "        // MUTANT\n", "", "RingSetupsIT"),
    ("M5 (E117 point 4) hasViewer ignores the live course", SRC + "courses/domain/RingSetupRules.java",
     "return liveCourse && (placementId != null || courseWithModel);", "return placementId != null || courseWithModel; // MUTANT",
     "RingSetupRulesTest", "RingSetupsIT"),
    ("M6 (E117 point 5) the placement's version checked after the policy", SRC + "courses/application/PlacementService.java",
     "        versionCheck.run();\n        var outcome = PlacementRules.outcome(in.warnings(), in.force());\n",
     "        var outcome = PlacementRules.outcome(in.warnings(), in.force()); // MUTANT\n", "", "PlacementsIT"),
    ("M7 (R-16-10) the build sheet is bound without claiming the upload", SRC + "courses/application/BuildSheetService.java",
     "        files.claim(fileKey, UploadPurpose.BUILD_SHEET, PlacementService.ENTITY, placement.id());\n", "        // MUTANT\n", "", "PlacementsIT"),
    ("M8 (step 1) the demo build session is never finished", SRC + "courses/application/DemoCoursesSeeder.java",
     "            sessions.finish(session.id());\n", "            // MUTANT\n", "", "DemoCoursesSeedIT"),
]


def failures():
    found = []
    for report in glob.glob("target/surefire-reports/TEST-*.xml") + glob.glob("target/failsafe-reports/TEST-*.xml"):
        for case in ET.parse(report).getroot().iter("testcase"):
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
