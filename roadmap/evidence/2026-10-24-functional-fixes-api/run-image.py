#!/usr/bin/env python3
"""Build the fixed local image after a successful clean gate, under heavy.sh."""
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]
EVIDENCE = Path(__file__).resolve().parent
IMAGE = "agilityhub-core-api:review-fixes-20261024"
ALIAS = "ghcr.io/jboixtcm/agilityhub-core-api:review-fixes-20261024"


def inputs():
    paths = [ROOT / name for name in ("Dockerfile", ".dockerignore", "pom.xml", "mvnw")]
    for directory in (".mvn", "src", "seeds"):
        paths.extend(path for path in (ROOT / directory).rglob("*") if path.is_file())
    return {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(paths)}


def record(name, command):
    prefix = EVIDENCE / name
    if prefix.with_suffix(".command").exists():
        raise RuntimeError("Refusing to replace evidence: " + name)
    prefix.with_suffix(".command").write_text(shlex.join(command) + "\nExecuted under shared heavy.sh lock.\n")
    print("START " + name, flush=True)
    with prefix.with_suffix(".log").open("w") as log:
        result = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
    prefix.with_suffix(".exit").write_text(str(result.returncode) + "\n")
    lines = prefix.with_suffix(".log").read_text(errors="replace").splitlines()
    (EVIDENCE / (name + "-tail.txt")).write_text("\n".join(lines[-40:]) + "\n")
    print("END " + name + ": " + str(result.returncode), flush=True)
    if result.returncode:
        sys.exit(result.returncode)


def main():
    gate = json.loads((EVIDENCE / "05-expanded-clean-verify-summary.json").read_text())
    tested = json.loads((EVIDENCE / "05-expanded-clean-verify-tested-inputs.json").read_text())
    if gate["exitCode"] != 0 or not tested["unchanged"] or gate["missingChangedClasses"]:
        raise RuntimeError("A successful unchanged clean gate is required")
    before = inputs()
    for path, digest in tested["sha256"].items():
        if hashlib.sha256((ROOT / path).read_bytes()).hexdigest() != digest:
            raise RuntimeError("Source changed after gate: " + path)
    for tag in (IMAGE, ALIAS):
        if subprocess.run(["docker", "image", "inspect", tag], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
            raise RuntimeError("Unique local tag already exists: " + tag)
    (EVIDENCE / "06-image-tested-inputs.json").write_text(json.dumps(before, indent=2) + "\n")
    record("06-image-build", ["docker", "build", "--progress=plain", "--platform=linux/arm64", "-t", IMAGE, "-t", ALIAS, "."])
    if inputs() != before:
        raise RuntimeError("Image inputs changed during build")
    images = json.loads(subprocess.check_output(["docker", "image", "inspect", IMAGE, ALIAS], cwd=ROOT))
    if images[0]["Id"] != images[1]["Id"]:
        raise RuntimeError("Local tags differ")
    item = images[0]
    metadata = {"imageId": item["Id"], "tags": [IMAGE, ALIAS], "architecture": item["Architecture"],
                "os": item["Os"], "created": item["Created"], "user": item["Config"]["User"],
                "entrypoint": item["Config"]["Entrypoint"], "repoDigests": item.get("RepoDigests", []),
                "testedInputsUnchanged": True,
                "scope": "Local arm64 image; no registry push, no deploy, no amd64 or hosted-provider verification."}
    (EVIDENCE / "06-image-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    record("07-image-runtime", ["docker", "run", "--rm", "--network", "none", "--entrypoint", "/bin/sh", IMAGE,
           "-c", 'test "$(id -u)" = 10001 && test ! -e /usr/bin/pebble && test -r /app/seeds/club-canic-demo.yaml && java -version && sha256sum /app/seeds/club-canic-demo.yaml /app/seeds/club-canic.yaml'])
    output = (EVIDENCE / "07-image-runtime.log").read_text()
    for name in ("club-canic-demo.yaml", "club-canic.yaml"):
        digest = hashlib.sha256((ROOT / "seeds" / name).read_bytes()).hexdigest()
        if digest + "  /app/seeds/" + name not in output:
            raise RuntimeError("Runtime seed differs: " + name)
    metadata["runtimeVerified"] = {"uid": 10001, "noNetwork": True, "unusedPebbleAbsent": True,
                                  "demoAndProductionSeedMatchSource": True, "javaVersionExecuted": True}
    (EVIDENCE / "06-image-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(json.dumps(metadata), flush=True)


if __name__ == "__main__":
    main()
