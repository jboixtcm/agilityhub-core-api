"""E6-T01: operations and schemas added/removed/changed in docs/openapi/openapi.json against a base snapshot (default: git HEAD)."""
import json
import subprocess
import sys

base_ref = sys.argv[1] if len(sys.argv) > 1 else "HEAD"
before = json.loads(subprocess.run(["git", "show", base_ref + ":docs/openapi/openapi.json"], capture_output=True, check=True, text=True).stdout)
after = json.load(open("docs/openapi/openapi.json", encoding="utf-8"))

def operations(api):
    return {(method.upper(), path): op for path, item in api["paths"].items() for method, op in item.items()}

old_ops, new_ops = operations(before), operations(after)
added = sorted(set(new_ops) - set(old_ops), key=lambda k: (k[1], k[0]))
print("operations: before=%d after=%d added=%d removed=%d" % (len(old_ops), len(new_ops), len(added), len(set(old_ops) - set(new_ops))))
for method, path in added:
    print("  + %-6s %s  (%s)" % (method, path, new_ops[(method, path)].get("operationId")))
changed_ops = sorted(k for k in set(old_ops) & set(new_ops) if old_ops[k] != new_ops[k])
print("operations changed: %d" % len(changed_ops))
for method, path in changed_ops:
    print("  ~ %-6s %s" % (method, path))
old_schemas, new_schemas = before["components"]["schemas"], after["components"]["schemas"]
print("schemas: before=%d after=%d added=%d removed=%d" % (len(old_schemas), len(new_schemas), len(set(new_schemas) - set(old_schemas)),
                                                           len(set(old_schemas) - set(new_schemas))))
print("  added: " + ", ".join(sorted(set(new_schemas) - set(old_schemas))))
print("  removed: " + ", ".join(sorted(set(old_schemas) - set(new_schemas))))
changed = sorted(k for k in set(old_schemas) & set(new_schemas) if old_schemas[k] != new_schemas[k])
print("  changed: " + ", ".join(changed))
for name in changed:
    old_props, new_props = set(old_schemas[name].get("properties", {})), set(new_schemas[name].get("properties", {}))
    print("    %s: +%s -%s" % (name, sorted(new_props - old_props), sorted(old_props - new_props)))
