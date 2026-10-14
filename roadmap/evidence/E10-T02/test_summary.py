from pathlib import Path
import xml.etree.ElementTree as ET
changed={"PlatformConsoleIT","E10ContractIT","E2ContractIT","ListFieldsContractIT","ConsoleThemeRulesTest","ConsolePaymentValidationTest","RateLimitIT","StripePaymentProviderTest","StripeWebhooksSurvivorsTest","EventCatalogContractTest"}
seen=set()
for directory in ("surefire-reports","failsafe-reports"):
    files=sorted(Path("target",directory).glob("TEST-*.xml"))
    assert files,f"No reports in {directory}"
    totals=dict.fromkeys(("tests","failures","errors","skipped"),0)
    entries=[]
    for file in files:
        suite=ET.parse(file).getroot()
        for key in totals:totals[key]+=int(suite.get(key,"0"))
        if suite.get("name","").split(".")[-1] in changed:
            seen.add(suite.get("name","").split(".")[-1])
            entries.append(suite.get("name")+": "+", ".join(f"{key}={suite.get(key,'0')}" for key in totals))
    print(directory+": "+", ".join(f"{key}={value}" for key,value in totals.items()))
    print("\n".join(entries))
    assert totals["failures"]==totals["errors"]==totals["skipped"]==0
assert seen==changed, f"Missing changed test classes: {sorted(changed-seen)}"
print("All clean-run test reports have zero failures, errors and skipped tests; every changed test class is present.")
