"""E5-T01 OpenAPI diff summary: operations, paths and schemas added/removed/changed against HEAD (read-only git)."""
import json
import subprocess

old = json.loads(subprocess.run(["git", "show", "HEAD:docs/openapi/openapi.json"], capture_output=True, check=True, text=True).stdout)
new = json.load(open("docs/openapi/openapi.json"))
METHODS = {"get", "post", "put", "patch", "delete"}

def operations(api):
    return {(m.upper(), p) for p, item in api["paths"].items() for m in item if m in METHODS}

added = sorted(operations(new) - operations(old), key=lambda o: (o[1], o[0]))
removed = sorted(operations(old) - operations(new))
print("operations added:", len(added))
for method, path in added:
    op = new["paths"][path][method.lower()]
    print("  %-6s %s  [%s]%s" % (method, path, op.get("summary"), "  x-filterable=" + ",".join(op["x-filterable"]) if "x-filterable" in op else ""))
print("operations removed:", len(removed), removed)
print("paths added:", len(set(new["paths"]) - set(old["paths"])))
changed_ops = sorted((m, p) for (m, p) in operations(old) & operations(new) if old["paths"][p][m.lower()] != new["paths"][p][m.lower()])
print("existing operations changed:", len(changed_ops), changed_ops)
schemas_old, schemas_new = old["components"]["schemas"], new["components"]["schemas"]
added_schemas = sorted(set(schemas_new) - set(schemas_old))
print("schemas added:", len(added_schemas))
print("  " + ", ".join(added_schemas))
print("schemas removed:", sorted(set(schemas_old) - set(schemas_new)))
print("existing schemas changed:", sorted(k for k in set(schemas_old) & set(schemas_new) if schemas_old[k] != schemas_new[k]))
