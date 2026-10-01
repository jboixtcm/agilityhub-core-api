"""E8-T01 evidence: what docs/openapi/openapi.json gains against HEAD (operations, paths, schemas, security schemes), the changed
operations and schemas, and the operations of tests/fixtures e8-routes.json split by spec (read-only `git show`)."""
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
before = json.loads(subprocess.run(["git", "show", "HEAD:docs/openapi/openapi.json"], cwd=ROOT, check=True, capture_output=True, text=True).stdout)
after = json.loads((ROOT / "docs/openapi/openapi.json").read_text())


def operations(api):
    return {f"{method.upper()} {path}": operation for path, item in api["paths"].items() for method, operation in item.items()
            if method in ("get", "post", "put", "patch", "delete")}


old, new = operations(before), operations(after)
added = sorted(set(new) - set(old))
print(f"ops added {len(added)} removed {len(set(old) - set(new))}")
print(f"paths added {len(set(after['paths']) - set(before['paths']))}")
schemas_before, schemas_after = before["components"]["schemas"], after["components"]["schemas"]
print(f"schemas added {len(set(schemas_after) - set(schemas_before))} removed {len(set(schemas_before) - set(schemas_after))}")
print(f"schemas changed {sorted(name for name in schemas_before if name in schemas_after and schemas_before[name] != schemas_after[name])}")
print(f"ops changed {sorted(key for key in old if key in new and old[key] != new[key])}")
print(f"security schemes added {sorted(set(after['components']['securitySchemes']) - set(before['components']['securitySchemes']))}")
print(f"AuditAction values added {sorted(set(schemas_after['AuditAction']['enum']) - set(schemas_before['AuditAction']['enum']))}")
request_before = schemas_before["CheckoutSessionRequest"]
request_after = schemas_after["CheckoutSessionRequest"]
print(f"CheckoutSessionRequest properties added {sorted(set(request_after['properties']) - set(request_before['properties']))}, "
      f"required before {sorted(request_before.get('required', []))} after {sorted(request_after.get('required', []))}")
upfront = schemas_after["UpfrontPayment"]
print(f"UpfrontPayment (new schema) properties {sorted(upfront['properties'])} required {sorted(upfront.get('required', []))}")
routes = json.loads((ROOT / "src/test/resources/fixtures/contracts/e8-routes.json").read_text())


def spec(path):
    return "S13" if any(word in path for word in ("inactivity", "leave", "reactivat")) else "S12"


by_spec = {}
for route in routes:
    by_spec.setdefault(spec(route["path"]), []).append(f"{route['method']} {route['path']}")
print("e8-routes.json " + ", ".join(f"{name}: {len(items)}" for name, items in sorted(by_spec.items())) + f", total {len(routes)}")
print(f"e8-routes.json routes missing from the snapshot {sorted(set(r for items in by_spec.values() for r in items) - set(new))}")
for key in added:
    print(f"  + [{spec(key)}] {key} ({new[key].get('summary', '')})")
