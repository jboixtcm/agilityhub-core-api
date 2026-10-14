import json,subprocess
from pathlib import Path
old=json.loads(subprocess.check_output(["git","show","b272803d^:docs/openapi/openapi.json"]))
new=json.loads(Path("docs/openapi/openapi.json").read_text())
methods={"get","post","put","patch","delete","head","options"}
def operations(api):return {(path,method):op for path,value in api["paths"].items() for method,op in value.items() if method in methods}
a,b=operations(old),operations(new)
for key in sorted(set(a)|set(b)):
    if a.get(key)!=b.get(key):
        print(("added" if key not in a else "removed" if key not in b else "changed"),key[1].upper(),key[0])
        assert key in b,"Removed operation"
        assert key[0].startswith("/api/v1/platform/clubs") or key[0] in {"/api/v1/platform/club-templates","/api/v1/platform/audit-entries","/api/v1/platform/security-events","/api/v1/public/{clubSlug}/theme/{fileKey}"},"Unrelated operation changed"
a,b=old["components"]["schemas"],new["components"]["schemas"]
for key in sorted(set(a)|set(b)):
    if a.get(key)!=b.get(key):
        print(("added" if key not in a else "removed" if key not in b else "changed"),"schema",key)
        assert key in b,"Removed schema"
        if key in a:assert key=="PlatformClubPage","Unrelated schema changed"
print("OpenAPI diff is confined to S17 console behavior, logo upload/public display, and appliedFilters.")
