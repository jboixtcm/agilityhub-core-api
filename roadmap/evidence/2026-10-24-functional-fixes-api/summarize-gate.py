#!/usr/bin/env python3
"""Capture generated-contract parity, coverage and immutable production-seed evidence."""
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
EVIDENCE = Path(__file__).resolve().parent


def counters(node):
    result = {}
    for item in node.findall("counter"):
        missed, covered = int(item.attrib["missed"]), int(item.attrib["covered"])
        result[item.attrib["type"]] = {"missed": missed, "covered": covered,
                                       "ratio": covered / (missed + covered) if missed + covered else 1.0}
    return result


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    summary = json.loads((EVIDENCE / "05-expanded-clean-verify-summary.json").read_text())
    assert summary["exitCode"] == 0, "Full gate must pass first"
    report = ET.parse(ROOT / "target/site/jacoco/jacoco.xml").getroot()
    packages = []
    new_classes = []
    for package in report.findall("package"):
        name = package.attrib["name"]
        parts = name.split("/")
        group = "domain/application" if any(part in parts for part in ("domain", "application")) else "api" if "api" in parts else None
        if group:
            counts = counters(package)
            thresholds = {"LINE": .85, "BRANCH": .80} if group == "domain/application" else {"LINE": .70}
            violations = [kind for kind, minimum in thresholds.items() if kind in counts and counts[kind]["ratio"] < minimum]
            packages.append({"package": name.replace("/", "."), "group": group, "counters": counts, "violations": violations})
        for cls in package.findall("class"):
            if cls.attrib["name"].split("/")[-1] in ("ConsentGrant", "ConsentProjection"):
                new_classes.append({"class": cls.attrib["name"].replace("/", "."), "counters": counters(cls)})
    data = {"overall": counters(report), "gatedPackages": len(packages), "violations": [item for item in packages if item["violations"]],
            "newClasses": new_classes, "packages": packages}
    (EVIDENCE / "05-coverage-summary.json").write_text(json.dumps(data, indent=2) + "\n")
    assert not data["violations"], "Coverage package threshold violation"
    snapshot, generated = ROOT / "docs/openapi/openapi.json", ROOT / "target/openapi.json"
    contract = {"reviewedSnapshotSHA256": digest(snapshot), "freshGeneratedSHA256": digest(generated),
                "byteIdentical": snapshot.read_bytes() == generated.read_bytes(),
                "source": "Fresh full clean verify OpenApiSnapshotTest output; no snapshot rewrite"}
    (EVIDENCE / "05-openapi-comparison.json").write_text(json.dumps(contract, indent=2) + "\n")
    assert contract["byteIdentical"], "Generated OpenAPI differs"
    baseline = json.loads((EVIDENCE / "00-production-seed-tested-inputs.json").read_text())
    seed = {"unchanged": all(digest(ROOT / path) == value for path, value in baseline.items()),
            "production": {path: digest(ROOT / path) for path in baseline},
            "demo": {"seeds/club-canic-demo.yaml": digest(ROOT / "seeds/club-canic-demo.yaml")}}
    (EVIDENCE / "05-seeds-tested-inputs.json").write_text(json.dumps(seed, indent=2) + "\n")
    assert seed["unchanged"], "Production seed changed"
    print(json.dumps({"coverageOverall": data["overall"], "gatedPackages": len(packages), "violations": 0,
                      "openApiByteIdentical": contract["byteIdentical"], "productionSeedUnchanged": seed["unchanged"]}))


if __name__ == "__main__":
    main()
