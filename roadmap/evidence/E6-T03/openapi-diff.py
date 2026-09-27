"""E6-T03: what changed in docs/openapi/openapi.json against a base snapshot (default: git HEAD), per operation and schema."""
import json
import subprocess
import sys

base_ref = sys.argv[1] if len(sys.argv) > 1 else "HEAD"
before = json.loads(subprocess.run(["git", "show", base_ref + ":docs/openapi/openapi.json"], capture_output=True, check=True, text=True).stdout)
after = json.load(open("docs/openapi/openapi.json", encoding="utf-8"))


def operations(api):
    return {(method.upper(), path): op for path, item in api["paths"].items() for method, op in item.items()}


def parts(old, new):
    """The keys of an operation that differ, with a short note for parameters and responses."""
    notes = []
    for key in sorted(set(old) | set(new)):
        if old.get(key) == new.get(key):
            continue
        if key == "parameters":
            o = {p["name"]: p for p in old.get(key, [])}
            n = {p["name"]: p for p in new.get(key, [])}
            changed = [name for name in sorted(set(o) & set(n)) if o[name] != n[name]]
            notes.append("parameters(+%s -%s ~%s)" % (sorted(set(n) - set(o)), sorted(set(o) - set(n)), changed))
        elif key == "responses":
            o, n = old.get(key, {}), new.get(key, {})
            notes.append("responses(+%s -%s ~%s)" % (sorted(set(n) - set(o)), sorted(set(o) - set(n)), sorted(c for c in set(o) & set(n) if o[c] != n[c])))
        else:
            notes.append(key)
    return ", ".join(notes)


old_ops, new_ops = operations(before), operations(after)
print("operations: before=%d after=%d added=%d removed=%d" % (len(old_ops), len(new_ops), len(set(new_ops) - set(old_ops)), len(set(old_ops) - set(new_ops))))
changed_ops = sorted((k for k in set(old_ops) & set(new_ops) if old_ops[k] != new_ops[k]), key=lambda k: (k[1], k[0]))
print("operations changed: %d" % len(changed_ops))
for method, path in changed_ops:
    print("  ~ %-6s %-40s %s" % (method, path, parts(old_ops[(method, path)], new_ops[(method, path)])))
old_schemas, new_schemas = before["components"]["schemas"], after["components"]["schemas"]
print("schemas: before=%d after=%d added=%s removed=%s" % (len(old_schemas), len(new_schemas), sorted(set(new_schemas) - set(old_schemas)),
                                                          sorted(set(old_schemas) - set(new_schemas))))
for name in sorted(k for k in set(old_schemas) & set(new_schemas) if old_schemas[k] != new_schemas[k]):
    o, n = old_schemas[name], new_schemas[name]
    props_o, props_n = o.get("properties", {}), n.get("properties", {})
    print("  ~ %s: properties +%s -%s ~%s; required +%s -%s" % (name, sorted(set(props_n) - set(props_o)), sorted(set(props_o) - set(props_n)),
          sorted(p for p in set(props_o) & set(props_n) if props_o[p] != props_n[p]), sorted(set(n.get("required", [])) - set(o.get("required", []))),
          sorted(set(o.get("required", [])) - set(n.get("required", [])))))
