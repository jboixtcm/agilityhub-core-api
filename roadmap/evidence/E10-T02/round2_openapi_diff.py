#!/usr/bin/env python3
"""Round 2 OpenAPI scope: every operation and schema that differs between HEAD's snapshot and the working tree's."""
import json, pathlib, subprocess, sys

root = pathlib.Path(__file__).resolve().parents[3]
before = json.loads(subprocess.run(["git", "-C", str(root), "show", "HEAD:docs/openapi/openapi.json"], check=True, capture_output=True).stdout)
after = json.loads((root / "docs/openapi/openapi.json").read_text())
changes = []
for path in sorted(set(before["paths"]) | set(after["paths"])):
    old, new = before["paths"].get(path, {}), after["paths"].get(path, {})
    for method in sorted(set(old) | set(new)):
        if old.get(method) != new.get(method):
            kind = "added" if method not in old else "removed" if method not in new else "changed"
            changes.append(f"{kind} {method.upper()} {path}")
for name in sorted(set(before["components"]["schemas"]) | set(after["components"]["schemas"])):
    old, new = before["components"]["schemas"].get(name), after["components"]["schemas"].get(name)
    if old != new:
        kind = "added" if old is None else "removed" if new is None else "changed"
        changes.append(f"{kind} schema {name}")
print("\n".join(changes))
allowed = {
    "removed POST /api/v1/platform/clubs/{clubId}/upload-url",
    "changed POST /api/v1/platform/clubs/{id}/theme/upload-url",
    "changed POST /api/v1/platform/clubs",
    "removed schema PlatformThemeUploadRequest",
    "changed schema PlatformThemeRequest",
    "changed schema PlatformClubPatch",
    "changed schema AuditAction",
}
unexpected = [change for change in changes if change not in allowed]
if unexpected:
    print("Outside round 2's scope:", *unexpected, sep="\n  ")
    sys.exit(1)
print("OpenAPI diff is confined to round 2: one logo upload route, definition/patch descriptions and the three audit actions.")
