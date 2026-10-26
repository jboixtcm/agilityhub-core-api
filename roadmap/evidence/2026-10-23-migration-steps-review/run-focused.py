#!/usr/bin/env python3
"""Run the coordinated focused gate while holding the host heavy.sh lock."""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
EVIDENCE = Path(__file__).resolve().parent
UNITS = (
    "MigrationStepsTest", "ReapplyApprovalTest", "MigrationReportsTest",
    "MigrationCensusStepsTest", "MigrationApplyStepsTest",
    "PlayoffExecutionCommandTest", "PlayoffAdapterTest",
)
INTEGRATIONS = (
    "MigrationExecutionIT", "MigrationStagedImportIT",
    "MigrationReportsIT", "MigrationReportCheckpointIT",
)


def inputs():
    paths = [ROOT / name for name in ("pom.xml", "mvnw", "Dockerfile")]
    for directory in (".mvn", "src", "seeds"):
        paths.extend(path for path in (ROOT / directory).rglob("*") if path.is_file())
    return {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(paths)}


def summarize(name, directory, selected, started):
    classes = []
    for path in sorted((ROOT / "target" / directory).glob("TEST-*.xml")):
        tree = ET.parse(path).getroot()
        if tree.attrib["name"].split(".")[-1] not in selected:
            continue
        classes.append({"class": tree.attrib["name"], "fresh": path.stat().st_mtime >= started - 1,
                        **{key: int(tree.attrib.get(key, 0)) for key in ("tests", "failures", "errors", "skipped")}})
    summary = {"classes": classes,
               "totals": {key: sum(item[key] for item in classes) for key in ("tests", "failures", "errors", "skipped")},
               "missing": sorted(set(selected) - {item["class"].split(".")[-1] for item in classes})}
    (EVIDENCE / (name + "-summary.json")).write_text(json.dumps(summary, indent=2) + "\n")
    print(name, json.dumps(summary["totals"]), "missing=" + str(summary["missing"]), flush=True)
    return not summary["missing"] and all(item["fresh"] for item in classes)


def record(name, command, suite=None):
    for suffix in (".command", ".log", ".exit"):
        if (EVIDENCE / (name + suffix)).exists():
            raise RuntimeError("Refusing to replace existing evidence: " + name)
    (EVIDENCE / (name + ".command")).write_text(shlex.join(command) + "\nExecuted under the shared host heavy.sh lock.\n")
    started = time.time()
    print("START " + name, flush=True)
    with (EVIDENCE / (name + ".log")).open("w") as log:
        result = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
    (EVIDENCE / (name + ".exit")).write_text(str(result.returncode) + "\n")
    fresh = True if suite is None else summarize(name, *suite, started)
    print("END " + name + ": exit " + str(result.returncode), flush=True)
    if result.returncode or not fresh:
        print("\n".join((EVIDENCE / (name + ".log")).read_text().splitlines()[-35:]), flush=True)
        raise SystemExit(result.returncode or 1)


def main():
    attempt = int(sys.argv[1]) if len(sys.argv) > 1 else 1
    integration_only = len(sys.argv) > 2 and sys.argv[2] in ("integration", "staged")
    selected_integrations = ("MigrationStagedImportIT",) if len(sys.argv) > 2 and sys.argv[2] == "staged" else INTEGRATIONS
    first = (attempt - 1) * 3 + 1
    manifest_path = EVIDENCE / f"{first:02d}-focused-tested-inputs.json"
    if manifest_path.exists():
        raise RuntimeError("Input evidence already exists; use a new attempt number")
    before = inputs()
    evidence = {"startedAt": datetime.now(timezone.utc).isoformat(), "scope": "Focused migration source/tests/seeds",
                "integrationOnly": integration_only, "files": before}
    manifest_path.write_text(json.dumps(evidence, indent=2) + "\n")
    try:
        record(f"{first:02d}-test-compile", ["./mvnw", "-q", "-DskipTests", "test-compile"])
        slot = first + 1
        if not integration_only:
            record(f"{slot:02d}-units", ["./mvnw", "-q", "-Dtest=" + ",".join(UNITS), "test"], ("surefire-reports", UNITS))
            slot += 1
        record(f"{slot:02d}-integration", ["./mvnw", "-q", "-Dit.test=" + ",".join(selected_integrations), "failsafe:integration-test", "failsafe:verify"],
               ("failsafe-reports", selected_integrations))
    finally:
        after = inputs()
        evidence["finishedAt"] = datetime.now(timezone.utc).isoformat()
        evidence["unchanged"] = before == after
        evidence["changedFiles"] = sorted(key for key in before.keys() | after.keys() if before.get(key) != after.get(key))
        manifest_path.write_text(json.dumps(evidence, indent=2) + "\n")
        if before != after:
            raise RuntimeError("Inputs changed during verification: " + str(evidence["changedFiles"]))


if __name__ == "__main__":
    main()
