#!/usr/bin/env python3
"""E7-T03 Verification, local stack: on a disposable Compose stack built from the working tree (bin/e5-smoke's helpers),
`club:apply seeds/club-canic.yaml` then `seed:demo --club=canic --seed=42` (a week 8+ days ahead, as the smokes; each twice,
the second run 0 changes), then as the Cànic's admin:
  1. GET /message-templates: one template per eligible catalog code and the four category counts;
  2. edit N-08a's body (PUT), POST …/preview in ca and es, POST …/reset — the three responses, truncated;
  3. a D10 preference change (PUT /members/{id}/notification-preferences) and the audit entries it and the template edit wrote
     (MEMBER_UPDATED, CATALOG_CHANGED), ids truncated.
The stack is removed at the end. Run from the repository root: python3 roadmap/evidence/E7-T03/local_stack.py"""
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[3]
loader = importlib.machinery.SourceFileLoader("e5smoke", str(ROOT / "bin" / "e5-smoke"))
spec = importlib.util.spec_from_loader("e5smoke", loader)
smoke_module = importlib.util.module_from_spec(spec)
loader.exec_module(smoke_module)
SEEDS = (("club:apply", "seeds/club-canic.yaml"), ("seed:demo", "--club=canic", "--seed=42", smoke_module.WEEK_START))


def short(value, size=240):
    text = json.dumps(value, ensure_ascii=False) if not isinstance(value, str) else value
    return text if len(text) <= size else text[:size] + "…[truncated]"


def cut(identifier):
    return (identifier or "")[:8] + "…"


def main():
    os.umask(0o077)
    with tempfile.TemporaryDirectory(prefix="agilityhub-e7t03-") as directory:
        s = smoke_module.Smoke(Path(directory), None)
        s.project = "e7t03-local-" + s.suffix
        s.compose_args[s.compose_args.index("--project-name") + 1] = s.project
        try:
            s.start(SEEDS, "Cànic")
            # club:apply seeded the templates (its `messageTemplates` section), before any API read creates one.
            seeded = s.mongo('db.message_templates.aggregate([{$match:{clubId:' + json.dumps(s.canic) + '}},{$group:{_id:null,n:{$sum:1},'
                             'codes:{$addToSet:"$code"}}}]).toArray().map(g=>({n:g.n,codes:g.codes.length}))[0]')
            locales = s.mongo('db.message_templates.findOne({clubId:' + json.dumps(s.canic) + ',code:"N-08a"}).title.values')
            print(f"after club:apply: {seeded['n']} message_templates ({seeded['codes']} codes); N-08a title languages {sorted(locales)}")
            smoke_module.require(seeded["n"] == 51, "club:apply seeds the 51 templates of seeds/club-canic.yaml")
            admin = s.login("admin@example.test", "clubs-admin")
            listing = s.call("GET", "/api/v1/message-templates", access=admin)
            codes = [item["code"] for item in listing["items"] if item["kind"] == "CATALOG"]
            print(f"GET /message-templates -> {len(listing['items'])} templates ({len(codes)} CATALOG, "
                  f"{len(set(codes))} distinct codes); countsByCategory {json.dumps(listing['countsByCategory'])}")
            print("codes: " + " ".join(codes))
            smoke_module.require(len(codes) == 51 and len(set(codes)) == 51, "one template per eligible code")
            n08a = next(item for item in listing["items"] if item["code"] == "N-08a")
            detail = s.call("GET", "/api/v1/message-templates/" + n08a["id"], access=admin)
            print("N-08a before: version " + str(detail["version"]) + ", customized " + str(detail["customized"]) + ", body.ca "
                  + short(detail["bodyI18n"]["ca"]))
            body = {"title": detail["titleI18n"], "body": dict(detail["bodyI18n"]), "smsBody": detail["smsBodyI18n"], "icon": detail["icon"],
                    "color": detail["color"], "matrix": detail["matrix"], "enabled": detail["enabled"], "version": detail["version"]}
            body["body"]["ca"] = ("[[class_date]] · [[class_time]] · [[class_description]]: la classe queda anul·lada. «[[admin_text]]» "
                                  "Pots reservar-ne una altra des de l'app.")
            saved = s.call("PUT", "/api/v1/message-templates/" + n08a["id"], access=admin, body=body)
            print("PUT N-08a -> version " + str(saved["version"]) + ", customized " + str(saved["customized"]) + ", lastChange "
                  + short(saved["lastChange"]) + ", body.ca " + short(saved["bodyI18n"]["ca"]))
            for locale in ("ca", "es"):
                preview = s.call("POST", "/api/v1/message-templates/" + n08a["id"] + "/preview", access=admin, body={"locale": locale})
                print(f"POST preview {locale} -> title {short(preview['title'])} | body {short(preview['body'])} | emailSubject "
                      f"{short(preview['emailSubject'])} | sms {short(preview['sms'])} | warnings {short(preview['warnings'])} | emailHtml "
                      f"{len(preview['emailHtml'])} chars")
            reset = s.call("POST", "/api/v1/message-templates/" + n08a["id"] + "/reset", access=admin)
            print("POST reset -> version " + str(reset["version"]) + ", customized " + str(reset["customized"]) + ", body.ca equals the seed: "
                  + str(reset["bodyI18n"] == reset["seedDefault"]["bodyI18n"]) + ", body.ca " + short(reset["bodyI18n"]["ca"]))
            member = s.mongo('db.members.findOne({clubId:' + json.dumps(s.canic) + ',status:"ACTIVE"},{_id:1})._id')
            prefs = s.call("PUT", f"/api/v1/members/{member}/notification-preferences", access=admin,
                           body={"emailByCategory": {"OPERATIONAL": True}, "reminderMinutesBefore": 120})
            print("PUT /members/<id>/notification-preferences -> " + short(prefs, 400))
            audits = s.mongo('db.audit_entries.find({clubId:' + json.dumps(s.canic) + ',action:{$in:["MEMBER_UPDATED","CATALOG_CHANGED"]},'
                             'entityType:{$in:["Member","MessageTemplate"]}}).sort({at:1}).toArray().map(a=>({action:a.action,entityType:a.entityType,'
                             'entityId:a.entityId,actorName:a.actorName,actorRole:a.actorRole,origin:a.origin,'
                             'changes:(a.changes||[]).map(c=>c.path+": "+JSON.stringify(c.before)+" -> "+JSON.stringify(c.after))}))')
            for entry in audits:
                entry["entityId"] = cut(entry["entityId"])
                entry["changes"] = [short(change, 160) for change in entry["changes"]]
                print("audit " + json.dumps(entry, ensure_ascii=False))
            smoke_module.require(any(a["action"] == "MEMBER_UPDATED" for a in audits) and sum(a["action"] == "CATALOG_CHANGED" for a in audits) == 2,
                                 "the D10 change and the two template changes are audited")
            print("PASS E7-T03 local stack")
        finally:
            s.cleanup()


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, OSError) as error:
        print("FAIL: " + str(error), file=sys.stderr)
        sys.exit(1)
