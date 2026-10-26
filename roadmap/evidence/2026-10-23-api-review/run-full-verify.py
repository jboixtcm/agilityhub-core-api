#!/usr/bin/env python3
"""Record a complete clean Maven gate; invoke while holding the host heavy lock."""
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


def inputs():
    paths = [ROOT / name for name in ("pom.xml", "mvnw", "Dockerfile", "docs/openapi/openapi.json")]
    for directory in ("src", ".mvn", "seeds"):
        paths.extend(path for path in (ROOT / directory).rglob("*") if path.is_file())
    return {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(paths)}


def changed_test_classes():
    changed = subprocess.check_output(["git", "diff", "--name-only", "HEAD"], cwd=ROOT, text=True).splitlines()
    added = subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard"], cwd=ROOT, text=True).splitlines()
    return sorted({Path(path).stem for path in changed + added if path.startswith("src/test/java/")
                   and path.endswith(("Test.java", "IT.java"))})


def summaries(started, changed):
    result = {}
    for directory in ("surefire-reports", "failsafe-reports"):
        classes = []
        for path in sorted((ROOT / "target" / directory).glob("TEST-*.xml")):
            tree = ET.parse(path).getroot()
            classes.append({"class": tree.attrib["name"], "fresh": path.stat().st_mtime >= started - 1,
                            **{key: int(tree.attrib.get(key, 0)) for key in ("tests", "failures", "errors", "skipped")}})
        result[directory] = {"totals": {key: sum(item[key] for item in classes) for key in ("tests", "failures", "errors", "skipped")},
                             "classCount": len(classes), "allFresh": all(item["fresh"] for item in classes), "classes": classes}
    all_classes = [item for phase in result.values() for item in phase["classes"]]
    result["changedClasses"] = [item for item in all_classes if item["class"].split(".")[-1] in changed]
    result["missingChangedClasses"] = sorted(set(changed) - {item["class"].split(".")[-1] for item in all_classes})
    return result


def main():
    number = int(sys.argv[1])
    prefix = f"{number:02d}-expanded-clean-verify"
    for suffix in (".command", ".log", ".exit", "-tested-inputs.json"):
        if (EVIDENCE / (prefix + suffix)).exists():
            raise RuntimeError("Refusing to replace existing evidence: " + prefix)
    before = inputs()
    manifest = {"startedAt": datetime.now(timezone.utc).isoformat(), "sha256": before}
    source_path = EVIDENCE / (prefix + "-tested-inputs.json")
    source_path.write_text(json.dumps(manifest, indent=2) + "\n")
    command = ["./mvnw", "-q", "clean", "verify"]
    (EVIDENCE / (prefix + ".command")).write_text(shlex.join(command) + "\nExecuted under the shared host heavy.sh lock.\n")
    changed = changed_test_classes()
    started = time.time()
    print("START " + prefix, flush=True)
    with (EVIDENCE / (prefix + ".log")).open("w") as log:
        result = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
    (EVIDENCE / (prefix + ".exit")).write_text(str(result.returncode) + "\n")
    after = inputs()
    manifest.update(finishedAt=datetime.now(timezone.utc).isoformat(), unchanged=before == after,
                    changedFiles=sorted(key for key in before.keys() | after.keys() if before.get(key) != after.get(key)))
    source_path.write_text(json.dumps(manifest, indent=2) + "\n")
    summary = summaries(started, changed)
    summary["exitCode"] = result.returncode
    (EVIDENCE / (prefix + "-summary.json")).write_text(json.dumps(summary, indent=2) + "\n")
    lines = [f"Exit: {result.returncode}; tested inputs unchanged: {before == after}"]
    for phase in ("surefire-reports", "failsafe-reports"):
        lines.append(f"{phase}: {summary[phase]['totals']}; all fresh: {summary[phase]['allFresh']}")
    for item in summary["changedClasses"]:
        lines.append(f"{item['class']}: tests={item['tests']}, failures={item['failures']}, errors={item['errors']}, skipped={item['skipped']}, fresh={item['fresh']}")
    lines.append("Missing changed test classes: " + str(summary["missingChangedClasses"]))
    (EVIDENCE / (prefix + ".summary")).write_text("\n".join(lines) + "\n")
    tail = "\n".join((EVIDENCE / (prefix + ".log")).read_text().splitlines()[-40:]) + "\n"
    (EVIDENCE / (prefix + "-tail.txt")).write_text(tail)
    print("\n".join(lines[:3]), flush=True)
    print(tail, flush=True)
    if before != after or summary["missingChangedClasses"]:
        raise SystemExit(result.returncode or 1)
    raise SystemExit(result.returncode)


if __name__ == "__main__":
    main()
