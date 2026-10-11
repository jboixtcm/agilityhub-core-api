#!/usr/bin/env python3
"""Prove that the isolated verification used the working tree's current inputs."""
import hashlib
import json
from pathlib import Path
import sys

snapshot = Path(sys.argv[1])
manifest_path = Path(sys.argv[2]) if len(sys.argv) > 2 else Path(__file__).with_name("12-input-manifest.json")
manifest = json.loads(manifest_path.read_text())
changed = []
for entry in manifest:
    name, expected = entry["path"], entry["sha256"]
    for label, root in (("working tree", Path.cwd()), ("verification snapshot", snapshot)):
        path = root / name
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            changed.append(f"{label}: {name}")
print(f"Checked {len(manifest)} tracked inputs in the working tree and isolated snapshot.")
for name in changed:
    print("CHANGED " + name)
assert not changed, "Verification inputs changed"
print("All inputs match the clean-verification snapshot.")
