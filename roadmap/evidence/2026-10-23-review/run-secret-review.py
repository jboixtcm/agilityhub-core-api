#!/usr/bin/env python3
"""Scan this review's changed sources or evidence without exposing matched values."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
EVIDENCE = Path(__file__).resolve().parent
REPOSITORIES = (ROOT, ROOT.parent / "agilityhub-core-web")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--scanner", required=True)
    parser.add_argument("--scope", choices=("source", "evidence"), required=True)
    parser.add_argument("--name", required=True)
    args = parser.parse_args()
    destination = EVIDENCE / (args.name + ".json")
    if destination.exists():
        raise RuntimeError("Evidence already exists; use a new attempt name")
    results = []
    for root in REPOSITORIES:
        paths = set()
        for command in (("git", "diff", "--name-only", "-z"),
                        ("git", "ls-files", "--others", "--exclude-standard", "-z")):
            paths.update(value for value in subprocess.check_output(command, cwd=root).decode().split("\0") if value)
        selected = []
        for relative in sorted(paths):
            path = root / relative
            evidence = relative.startswith("roadmap/evidence/")
            if path.is_symlink() or not path.is_file():
                continue
            if args.scope == "source" and evidence:
                continue
            if args.scope == "evidence" and not relative.startswith("roadmap/evidence/2026-10-23-"):
                continue
            selected.append(relative)
        before = {name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in selected}
        with tempfile.TemporaryDirectory(prefix="agilityhub-review-scan-") as temporary:
            temporary = Path(temporary)
            source = temporary / "input"
            source.mkdir()
            for name in selected:
                target = source / name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(root / name, target)
            report = temporary / "findings.json"
            configuration = root / ".gitleaks.toml"
            if not configuration.exists():
                configuration = ROOT / ".gitleaks.toml"
            command = [args.scanner, "dir", "--no-banner", "--redact", "--config", str(configuration),
                       "--report-format", "json", "--report-path", str(report), str(source)]
            completed = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            findings = json.loads(report.read_text()) if report.exists() else []
            results.append({"repository": root.name, "files": len(selected), "exit_code": completed.returncode,
                            "findings": len(findings), "command": command[:-1] + ["<private-temporary-copy>"],
                            "locations": [{"file": str(item["File"]).replace(str(source) + "/", ""),
                                           "line": item["StartLine"], "rule": item["RuleID"]} for item in findings],
                            "sourceUnchanged": all((root / name).exists() and hashlib.sha256((root / name).read_bytes()).hexdigest() == digest
                                                   for name, digest in before.items())})
    output = {"scannerVersion": subprocess.check_output([args.scanner, "version"], text=True).strip(),
              "scope": args.scope, "results": results}
    destination.write_text(json.dumps(output, indent=2) + "\n")
    print(json.dumps({"evidence": str(destination), "scope": args.scope,
                      "results": [{key: row[key] for key in ("repository", "files", "exit_code", "findings", "sourceUnchanged")}
                                  for row in results]}, indent=2))
    raise SystemExit(0 if all(row["exit_code"] == 0 and row["sourceUnchanged"] for row in results) else 1)


if __name__ == "__main__":
    main()
