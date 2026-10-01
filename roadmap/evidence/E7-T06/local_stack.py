#!/usr/bin/env python3
"""E7-T06 Verification, local stack (E7-T03 round 2's messaging checks, with this task's; round 2 of 01-10): on a disposable
Compose stack built from the working tree (bin/e5-smoke's helpers), `club:apply` then `seed:demo --seed=42` of the Cànic and
of the FIFO club (each twice, the second run 0 changes), then as the Cànic's admin:
  1. E7-T03's checks: the 51 templates, N-02's variables without `link`, D10's read;
  2. step 1, never edited: the Cànic's N-02 and N-15 are set (in Mongo) to the literal texts E7-T02's first use stored, the
     defect is shown (D9's preview of N-02 greets Laura «Benvingut» and keeps the link sentence), the API is restarted, and
     the start-up upgrade gives both the current seed — in ca/es and in the `en` they store (round 2: never a language
     dropped) — with a MessageTemplateChanged and a CATALOG_CHANGED by system:template-upgrade (D9's «last change»):
     GET then an unchanged PUT → 200, the preview «Benvinguda»;
  3. second restart, with four stored templates and one unreadable one:
     - step 1, edited: N-02 set to an edited round-1 text (customized, `[[link]]`): the unchanged PUT is 400
       TEMPLATE_UNKNOWN_VARIABLE; after the restart the club's words stay without the link sentence and the PUT is 200;
     - round 2, review #1: N-09 edited in ca/es/en (the Cànic is ca/es: a language the club removed): after the restart
       the English words are still stored, nothing written, published or audited;
     - round 2, review #3: N-08b edited with E7-T03's `{class_description}`: PUT 400 before, after the restart the argument
       is gone, the club's words stay and the PUT is 200;
     - round 2, review #2: a template of the FIFO club that the API cannot read: the API starts all the same, logs one WARN
       with the FIFO club's id only, and the Cànic's templates above are upgraded;
  4. step 3: an account's language `en` (the Cànic is ca/es) → D10's `locale` is `en`;
  5. step 5 and P1: the seed of N-08b (D9's `seedDefault`) prints only its row's variables; N-32c's row has `date` and its
     seed SMS prints «([[date]])».
The stack is removed at the end. Run from the repository root: python3 roadmap/evidence/E7-T06/local_stack.py"""
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
ACTOR = "system:template-upgrade"

# The literal texts of E7-T02's first use (`messages_{ca,es,en}.properties` of ca83d97^) and of E7-T03's round-1 seed (ca83d97).
E7T02_N02 = {
    "title": {"ca": "{gender, select, FEMALE {Benvinguda} other {Benvingut}}, {member_first_name}!",
              "es": "{gender, select, FEMALE {¡Bienvenida} other {¡Bienvenido}}, {member_first_name}!",
              "en": "{gender, select, FEMALE {Welcome} other {Welcome}}, {member_first_name}!"},
    "body": {"ca": "La teva alta a {club_name} està validada. Entra al teu compte amb aquest enllaç.",
             "es": "Tu alta en {club_name} está validada. Accede a tu cuenta con este enlace.",
             "en": "Your membership at {club_name} is active. Use the link to access your account."}}
E7T02_N15 = {
    "title": {"ca": "S'ha alliberat una plaça!", "es": "¡Se ha liberado una plaza!", "en": "A place has been freed up!"},
    "body": {"ca": "Classe {class_description} · {class_date} · {class_time}, amb {dog_name}. {mode, select, FIFO {La plaça és per a tu fins a les {confirm_by}: toca «Agafa la plaça» per confirmar-la.} other {Estàs a la llista d'espera — la plaça és per a qui confirmi primer.}}",
             "es": "Clase {class_description} · {class_date} · {class_time}, con {dog_name}. {mode, select, FIFO {La plaza es para ti hasta las {confirm_by}: toca «Coger la plaza» para confirmarla.} other {Estás en la lista de espera: la plaza es para quien la confirme primero.}}",
             "en": "{class_description} class · {class_date} · {class_time}, with {dog_name}. {mode, select, FIFO {The place is yours until {confirm_by}: tap «Take the place» to confirm it.} other {You are on the waiting list: the place goes to whoever confirms first.}}"},
    "smsBody": {"ca": "{club_name}: plaça lliure a la classe {class_date} {class_time} ({class_description}). {mode, select, FIFO {Confirma-la a l'app abans de les {confirm_by}.} other {Entra a l'app per agafar-la.}}",
                "es": "{club_name}: plaza libre en la clase {class_date} {class_time} ({class_description}). {mode, select, FIFO {Confírmala en la app antes de las {confirm_by}.} other {Entra en la app para cogerla.}}",
                "en": "{club_name}: a place is free in the class {class_date} {class_time} ({class_description}). {mode, select, FIFO {Confirm it in the app before {confirm_by}.} other {Take it in the app.}}"}}
ROUND1_N02_EDITED = {
    "title": {"ca": "{gender, select, female {Benvinguda} other {Benvingut}} a [[club_name]], [[member_first_name]]!",
              "es": "{gender, select, female {¡Bienvenida} other {¡Bienvenido}} a [[club_name]], [[member_first_name]]!"},
    "body": {"ca": "Ja pots entrar a l'app del nostre club. Entra-hi amb aquest enllaç: [[link]]. Fins aviat!",
             "es": "Ya tienes acceso a la app del club. Entra con este enlace: [[link]]."}}
# Round 2: an N-09 the club edited in three languages before it removed `en`, and an N-08b edited on E7-T03's seed (d46818b^).
N09_EDITED = {
    "title": {"ca": "[[dog_name_article]] ja és de nivell [[level_name]]!", "es": "¡[[dog_name_article]] ya es de nivel [[level_name]]!",
              "en": "[[dog_name]] moves up to [[level_name]]!"},
    "body": {"ca": "Enhorabona! Les reserves ja fetes segueixen valent.", "es": "¡Enhorabuena! Las reservas hechas siguen valiendo.",
             "en": "Well done! Your bookings stay valid."}}
N08B_EDITED = {
    "title": {"ca": "Classe modificada pel club", "es": "Clase modificada por el club"},
    "body": {"ca": "[[class_date]] · {class_description}, amb [[dog_name]]: la classe ha canviat. [[changes]]. Truca'ns si cal.",
             "es": "[[class_date]] · {class_description}, con [[dog_name]]: la clase ha cambiado. [[changes]]. Consulta la app."},
    "smsBody": {"ca": "[[club_name]]: classe [[class_date]] amb [[dog_name]] modificada. [[changes]].",
                "es": "[[club_name]]: clase [[class_date]] con [[dog_name]] modificada. [[changes]]."}}


def short(value, size=240):
    text = json.dumps(value, ensure_ascii=False) if not isinstance(value, str) else value
    return text if len(text) <= size else text[:size] + "…[truncated]"


def localized(values):
    return {"values": values, "defaultLocale": "ca"}


def main():
    os.umask(0o077)
    with tempfile.TemporaryDirectory(prefix="agilityhub-e7t06-") as directory:
        s = smoke_module.Smoke(Path(directory), None)
        s.project = "e7t06-local-" + s.suffix
        s.compose_args[s.compose_args.index("--project-name") + 1] = s.project
        # The smoke's API logs at ERROR only (LOGGING_LEVEL_ROOT); the upgrade's WARN of round 2 #2 needs its package at WARN.
        override = Path(directory) / "compose.override.json"
        settings = json.loads(override.read_text())
        settings["services"][s.service]["environment"]["LOGGING_LEVEL_COM_AGILITYHUB_CORE_CLUBS_MESSAGING_APPLICATION"] = "WARN"
        override.write_text(json.dumps(settings))
        try:
            s.start(smoke_module.SEEDS, "Cànic, FIFO club")
            club = json.dumps(s.canic)

            session = {}

            def template(code):
                return s.mongo("(t => ({...t, version: Number(t.version)}))(db.message_templates.findOne({clubId:" + club + ",code:" + json.dumps(code)
                               + "},{title:1,body:1,smsBody:1,customized:1,version:1,updatedBy:1}))")

            def set_template(code, texts, customized):
                fields = {"title": localized(texts["title"]), "body": localized(texts["body"]), "customized": customized, "version": 0,
                          "updatedBy": "account-admin" if customized else "system:notification-engine"}
                unset = {} if "smsBody" in texts else {"smsBody": ""}
                if "smsBody" in texts:
                    fields["smsBody"] = localized(texts["smsBody"])
                script = "db.message_templates.updateOne({clubId:" + club + ",code:" + json.dumps(code) + "},{$set:" + json.dumps(fields, ensure_ascii=False) \
                    + (",$unset:" + json.dumps(unset) if unset else "") + "}).modifiedCount"
                smoke_module.require(s.mongo(script) == 1, code + " set to its old text")

            def changes(code):
                """The outbox's MessageTemplateChanged of the Cànic's template of `code`: count and the last one's actor."""
                return s.mongo("(e => ({count: e.length, actor: e.length ? e[e.length-1].actorAccountId : null, origin: e.length ? e[e.length-1].origin : null}))"
                               "(db.domain_events.find({clubId:" + club + ",type:'MessageTemplateChanged',aggregateId:" + json.dumps(ids[code]) + "}).sort({occurredAt:1}).toArray())")

            def audits(code):
                return s.mongo("(e => ({count: e.length, actor: e.length ? e[e.length-1].actorName : null}))(db.audit_entries.find({clubId:" + club
                               + ",entityType:'MessageTemplate',entityId:" + json.dumps(ids[code]) + "}).sort({at:1}).toArray())")

            def restart():
                s.compose("restart", s.service)

                def healthy():
                    try:
                        return s.call("GET", "/api/v1/health", quiet=True)["status"] == "UP"
                    except AssertionError:
                        return False
                smoke_module.wait(healthy, "API health after the restart", timeout=180)
                # The local profile's signing keys do not outlive the process: sign in again.
                session["admin"] = s.login("admin@example.test", "clubs-admin")
                print("PASS API restarted (start-up upgrade ran); admin signed in again", flush=True)

            def put_unchanged(item_id, expected, error=None):
                detail = s.call("GET", "/api/v1/message-templates/" + item_id, access=session["admin"])
                body = {"title": detail["titleI18n"], "body": detail["bodyI18n"], "smsBody": detail["smsBodyI18n"], "icon": detail["icon"], "color": detail["color"],
                        "matrix": detail["matrix"], "enabled": detail["enabled"], "version": detail["version"]}
                return detail, s.call("PUT", "/api/v1/message-templates/" + item_id, expected, access=session["admin"], body=body, error=error)

            def preview(item_id, locale="ca"):
                return s.call("POST", "/api/v1/message-templates/" + item_id + "/preview", access=session["admin"], body={"locale": locale})

            session["admin"] = s.login("admin@example.test", "clubs-admin")
            listing = s.call("GET", "/api/v1/message-templates", access=session["admin"])
            codes = [item["code"] for item in listing["items"] if item["kind"] == "CATALOG"]
            smoke_module.require(len(codes) == 51 and len(set(codes)) == 51, "one template per eligible code")
            ids = {item["code"]: item["id"] for item in listing["items"] if item["kind"] == "CATALOG"}
            variables = {item["code"]: [v["key"] for v in item["variables"]] for item in listing["items"] if item["kind"] == "CATALOG"}
            print(f"GET /message-templates -> {len(codes)} CATALOG templates; N-02 variables {', '.join(variables['N-02'])}; "
                  f"N-32c variables {', '.join(variables['N-32c'])}")
            smoke_module.require("link" not in variables["N-02"], "N-02's template has no link (E76)")
            smoke_module.require("date" in variables["N-32c"], "N-32c's row has date (P1, E81)")

            # 2. Step 1, never edited: E7-T02's N-02 and N-15.
            set_template("N-02", E7T02_N02, False)
            set_template("N-15", E7T02_N15, False)
            before = preview(ids["N-02"])
            print("step 1 before (E7-T02 N-02 stored): preview ca title " + short(before["title"]) + " | body " + short(before["body"]))
            smoke_module.require(before["title"].startswith("Benvingut,") and "enllaç" in before["body"], "the E7-T02 defect is visible before the upgrade")
            events_before = {code: changes(code)["count"] for code in ("N-02", "N-15")}
            restart()
            for code in ("N-02", "N-15"):
                stored = template(code)
                print(f"step 1 after: {code} version {stored['version']}, customized {stored['customized']}, updatedBy {stored['updatedBy']}, "
                      f"languages {sorted(stored['body']['values'])}, body.ca {short(stored['body']['values']['ca'], 200)}")
                smoke_module.require(stored["updatedBy"] == ACTOR and sorted(stored["body"]["values"]) == ["ca", "en", "es"] and not stored["customized"],
                                     code + " took the current seed, its en kept")
                event, entry = changes(code), audits(code)
                print(f"step 1 after: {code} MessageTemplateChanged {events_before[code]} -> {event['count']} (actor {event['actor']}, origin {event['origin']}); "
                      f"audit actor {entry['actor']}")
                smoke_module.require(event["count"] == events_before[code] + 1 and event["actor"] == ACTOR and event["origin"] == "SYSTEM" and entry["actor"] == ACTOR,
                                     code + ": one event and one audit entry by the upgrade")
                detail, saved = put_unchanged(ids[code], 200)
                smoke_module.require(detail["bodyI18n"] == detail["seedDefault"]["bodyI18n"], code + " equals the seed")
                smoke_module.require(detail["lastChange"]["actorName"] == ACTOR, code + ": D9's last change names the upgrade")
                print(f"step 1 after: {code} GET (lastChange {detail['lastChange']['actorName']}) then unchanged PUT -> 200, version {saved['version']}, "
                      f"customized {saved['customized']}")
            after = preview(ids["N-02"])
            print("step 1 after: N-02 preview ca title " + short(after["title"]) + " | body " + short(after["body"]))
            smoke_module.require(after["title"].startswith("Benvinguda") and "enllaç" not in after["body"], "«Benvinguda», no link sentence")

            # 3. The second restart: an edited round-1 N-02, an N-09 with a language the club removed, an edited N-08b, an unreadable FIFO template.
            set_template("N-02", ROUND1_N02_EDITED, True)
            refused = put_unchanged(ids["N-02"], 400, "TEMPLATE_UNKNOWN_VARIABLE")[1]
            print("step 1 edited, before: unchanged PUT -> 400 TEMPLATE_UNKNOWN_VARIABLE details " + short(refused["details"]))
            print("step 1 edited, before: preview ca body " + short(preview(ids["N-02"])["body"]))
            set_template("N-09", N09_EDITED, True)
            set_template("N-08b", N08B_EDITED, True)
            refused = put_unchanged(ids["N-08b"], 400, "TEMPLATE_UNKNOWN_VARIABLE")[1]
            print("round 2 #3, before: N-08b unchanged PUT -> 400 TEMPLATE_UNKNOWN_VARIABLE details " + short(refused["details"]))
            n09_events, n09_audits = changes("N-09")["count"], audits("N-09")["count"]
            unreadable = {"_id": "e7t06-unreadable", "clubId": s.fifo, "code": "N-09", "kind": "BOGUS", "category": "PERSONAL", "status": "ACTIVE", "version": 0}
            smoke_module.require(s.mongo("db.message_templates.insertOne(" + json.dumps(unreadable) + ").acknowledged") is True, "unreadable FIFO template stored")
            print("round 2 #2, before: a FIFO club template with kind BOGUS (unreadable) stored")
            restart()
            api_log = s.compose("logs", "--no-color", s.service)
            warnings = [line[line.index("Message templates"):] for line in api_log.splitlines() if "WARN" in line and "Message templates" in line]
            print("round 2 #2, after: health UP; upgrade WARN lines: " + short(warnings, 400))
            smoke_module.require(warnings == ["Message templates of a club not brought up to date club=" + s.fifo + " error=IllegalArgumentException"],
                                 "one WARN, the FIFO club's id only")
            smoke_module.require(s.mongo("db.message_templates.deleteOne({_id:'e7t06-unreadable'}).deletedCount") == 1, "unreadable FIFO template removed")
            stored = template("N-02")
            print(f"step 1 edited, after: version {stored['version']}, customized {stored['customized']}, body {short(stored['body']['values'], 300)}")
            smoke_module.require(stored["body"]["values"]["ca"] == "Ja pots entrar a l'app del nostre club. Fins aviat!", "the club's words stay, without the link")
            saved = put_unchanged(ids["N-02"], 200)[1]
            print(f"step 1 edited, after: unchanged PUT -> 200, version {saved['version']}, customized {saved['customized']}; preview ca "
                  + short(preview(ids["N-02"])["body"]))
            stored = template("N-09")
            print(f"round 2 #1, after: N-09 version {stored['version']}, updatedBy {stored['updatedBy']}, body {short(stored['body']['values'], 300)}; "
                  f"MessageTemplateChanged {n09_events} -> {changes('N-09')['count']}, audit {n09_audits} -> {audits('N-09')['count']}")
            smoke_module.require(stored["body"]["values"] == N09_EDITED["body"] and stored["title"]["values"] == N09_EDITED["title"] and stored["version"] == 0
                                 and changes("N-09")["count"] == n09_events and audits("N-09")["count"] == n09_audits, "N-09 untouched, its en words kept")
            shown = s.call("GET", "/api/v1/message-templates/" + ids["N-09"], access=session["admin"])
            print(f"round 2 #1, after: D9 shows N-09 in {sorted(shown['bodyI18n'])}")
            stored = template("N-08b")
            print(f"round 2 #3, after: N-08b version {stored['version']}, updatedBy {stored['updatedBy']}, body {short(stored['body']['values'], 300)}")
            smoke_module.require(stored["body"]["values"]["ca"] == "[[class_date]], amb [[dog_name]]: la classe ha canviat. [[changes]]. Truca'ns si cal."
                                 and changes("N-08b")["actor"] == ACTOR, "N-08b without class_description, by the upgrade")
            saved = put_unchanged(ids["N-08b"], 200)[1]
            print(f"round 2 #3, after: N-08b unchanged PUT -> 200, version {saved['version']}, customized {saved['customized']}")

            # 4. Step 3: the account's own language.
            member = s.mongo('db.members.findOne({clubId:' + club + ',status:"ACTIVE",accountId:{$ne:null}},{_id:1,accountId:1})')
            s.mongo('db.accounts.updateOne({_id:' + json.dumps(member["accountId"]) + '},{$set:{locale:"en"}}).modifiedCount')
            prefs = s.call("GET", f"/api/v1/members/{member['_id']}/notification-preferences", access=session["admin"])
            print(f"step 3: Account.locale en in a ca/es club -> D10 locale {prefs['locale']}, availableLocales {prefs['availableLocales']}")
            smoke_module.require(prefs["locale"] == "en" and prefs["availableLocales"] == ["ca", "es"], "D10 answers the account's own language")

            # 5. Step 5 and P1: the seed prints only its rows' variables; N-32c's SMS prints its date again.
            n08b = s.call("GET", "/api/v1/message-templates/" + ids["N-08b"], access=session["admin"])["seedDefault"]["bodyI18n"]
            n32c = s.call("GET", "/api/v1/message-templates/" + ids["N-32c"], access=session["admin"])["seedDefault"]["smsBodyI18n"]
            print("step 5: N-08b seed body " + short(n08b) + " | N-32c seed SMS " + short(n32c))
            smoke_module.require("class_description" not in json.dumps(n08b) and all("([[date]])" in text for text in n32c.values()), "the seed prints its rows' variables")
            print("PASS E7-T06 local stack")
        finally:
            s.cleanup()


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, OSError) as error:
        print("FAIL: " + str(error), file=sys.stderr)
        sys.exit(1)
