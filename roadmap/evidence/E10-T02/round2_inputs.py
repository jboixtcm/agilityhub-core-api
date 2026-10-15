#!/usr/bin/env python3
"""Round 2: no build input changed after the final clean run started (its .command.txt is written at start by run.py)."""
import hashlib, pathlib, sys

root = pathlib.Path(__file__).resolve().parents[3]
started = (root / "roadmap/evidence/E10-T02/55-round2-clean-verify-all.command.txt").stat().st_mtime
inputs = [p for name in ("src", "docs", "seeds", ".mvn") for p in (root / name).rglob("*") if p.is_file()]
inputs += [root / "pom.xml", root / "mvnw"]
newer = sorted(str(p.relative_to(root)) for p in inputs if p.stat().st_mtime > started)
digest = hashlib.sha256()
for path in sorted(inputs):
    digest.update(str(path.relative_to(root)).encode()); digest.update(path.read_bytes())
print(f"{len(inputs)} build inputs; digest {digest.hexdigest()[:12]}…[truncated]")
if newer:
    print("Changed after the clean run started:", *newer, sep="\n  ")
    sys.exit(1)
print("No build input changed after attempt 55 started.")
