#!/usr/bin/env python3
"""Local final release proof. Run once under the host heavy lock after clean verify."""
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]
EVIDENCE = Path(__file__).resolve().parent
IMAGE = "agilityhub-core-api:review-20261023"
COMPOSE_IMAGE = "ghcr.io/jboixtcm/agilityhub-core-api:review-20261023"


def record(name, command, *, env=None):
    stem = EVIDENCE / name
    if stem.with_suffix(".command").exists():
        raise RuntimeError(f"Evidence already exists: {name}")
    stem.with_suffix(".command").write_text(shlex.join(command) + "\n")
    print(f"START {name}", flush=True)
    with stem.with_suffix(".log").open("w") as log:
        result = subprocess.run(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
    stem.with_suffix(".exit").write_text(str(result.returncode) + "\n")
    print(f"END {name}: exit {result.returncode}", flush=True)
    if result.returncode:
        sys.exit(result.returncode)


def source_manifest():
    paths = [ROOT / name for name in ("Dockerfile", ".dockerignore", "pom.xml", "mvnw")]
    for directory in (".mvn", "src", "seeds"):
        paths.extend(path for path in (ROOT / directory).rglob("*") if path.is_file())
    return {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(paths)}


def main():
    if (EVIDENCE / "15-final-image-build.command").exists():
        raise RuntimeError("Final image build evidence already exists; review it before an explicit recovery")
    for tag in (IMAGE, COMPOSE_IMAGE):
        if subprocess.run(["docker", "image", "inspect", tag], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
            raise RuntimeError(f"The unique review tag already exists: {tag}")
    before = source_manifest()
    (EVIDENCE / "final-image-tested-inputs.json").write_text(json.dumps(before, indent=2) + "\n")
    record("15-final-image-build", ["docker", "build", "--progress=plain", "--platform=linux/arm64",
           "-t", IMAGE, "-t", COMPOSE_IMAGE, "."])
    if before != source_manifest():
        raise RuntimeError("Build source changed during Docker build; image needs review before use")
    inspected = json.loads(subprocess.check_output(["docker", "image", "inspect", IMAGE, COMPOSE_IMAGE], cwd=ROOT))
    if inspected[0]["Id"] != inspected[1]["Id"]:
        raise RuntimeError("The two local review tags are not the same image")
    first = inspected[0]
    metadata = {"imageId": first["Id"], "tags": [IMAGE, COMPOSE_IMAGE], "architecture": first["Architecture"],
                "os": first["Os"], "created": first["Created"], "user": first["Config"]["User"],
                "entrypoint": first["Config"]["Entrypoint"], "repoDigests": first.get("RepoDigests", []),
                "scope": "Local arm64 build only; no registry push, no remote deploy, amd64 not verified here"}
    (EVIDENCE / "final-image-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    record("16-final-image-runtime", ["docker", "run", "--rm", "--network", "none", "--entrypoint", "/bin/sh", IMAGE,
           "-c", 'test ! -e /usr/bin/pebble && test "$(id -u)" = 10001 && java -version'])
    output = EVIDENCE / "final-image-scan-raw.json"
    cache = ROOT / ".local/security-tools/db"
    command = ["env", "TRIVY_CACHE_DIR=" + str(cache), str(ROOT / ".local/security-tools/trivy-0.74.0"),
               "image", "--skip-db-update", "--skip-java-db-update", "--offline-scan", "--skip-version-check",
               "--image-src", "docker", "--scanners", "vuln", "--list-all-pkgs", "--no-progress", "--format", "json", "--output", str(output), IMAGE]
    record("17-final-image-scan", command)
    scan = json.loads(output.read_text())
    findings = []
    java_packages = 0
    for result in scan.get("Results", []):
        if result.get("Type") == "jar":
            java_packages += len(result.get("Packages", []))
        for item in result.get("Vulnerabilities", []):
            findings.append({"target": result["Target"], "type": result.get("Type"), "id": item["VulnerabilityID"],
                             "package": item["PkgName"], "installed": item["InstalledVersion"], "fixed": item.get("FixedVersion"),
                             "severity": item["Severity"], "packagePath": item.get("PkgPath")})
    databases = {}
    for name in ("db/metadata.json", "java-db/metadata.json"):
        path = cache / name
        if path.exists():
            databases[name] = json.loads(path.read_text())
    summary = {"mode": "offline cached advisory database; final local arm64 image", "image": metadata,
               "advisoryDatabase": databases, "vulnerabilities": len(findings), "javaPackages": java_packages,
               "severityCounts": {severity: sum(item["severity"] == severity for item in findings)
                                  for severity in ("CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN")},
               "goFindings": sum(item["type"] == "gobinary" for item in findings), "findings": findings}
    (EVIDENCE / "final-image-scan-summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print("SCAN " + json.dumps(summary["severityCounts"]), flush=True)
    if java_packages == 0:
        raise RuntimeError("The final image scan did not inventory Java packages")
    record("18-final-compose-smoke", ["bin/deploy-smoke", "--image-tag", "review-20261023", "--skip-helper-builds",
           "--evidence-prefix", str(EVIDENCE / "19-compose-child")])


if __name__ == "__main__":
    main()
