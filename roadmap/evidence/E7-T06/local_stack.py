#!/usr/bin/env python3
"""E7-T06 Verification, local stack (E7-T03 round 2's messaging checks, with this task's): on a disposable Compose stack built
from the working tree (bin/e5-smoke's helpers), `club:apply seeds/club-canic.yaml` then `seed:demo --club=canic --seed=42`
(each twice, the second run 0 changes), then as the Cànic's admin:
  1. E7-T03's checks: the 51 templates, N-02's variables without `link`, D10's read;
  2. step 1, never edited: the Cànic's N-02 and N-15 are set (in Mongo) to the literal texts E7-T02's first use stored, the
     defect is shown (D9's preview of N-02 greets Laura «Benvingut» and keeps the link sentence), the API is restarted, and
     the start-up upgrade gives both the current seed in ca/es: GET then an unchanged PUT → 200, the preview «Benvinguda»;
  3. step 1, edited: N-02 is set to an edited round-1 text (customized, `[[link]]`): the unchanged PUT is 400
     TEMPLATE_UNKNOWN_VARIABLE; after a restart the club's words stay without the link sentence and the PUT is 200;
  4. step 3: an account's language `en` (the Cànic is ca/es) → D10's `locale` is `en`;
  5. step 5: the seed of N-08b and N-32c (D9's `seedDefault`) prints only its row's variables.
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
SEEDS = (("club:apply", "seeds/club-canic.yaml"), ("seed:demo", "--club=canic", "--seed=42", smoke_module.WEEK_START))

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
        try:
            s.start(SEEDS, "Cànic")
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
            n02_vars = [v["key"] for v in next(item for item in listing["items"] if item["code"] == "N-02")["variables"]]
            print(f"GET /message-templates -> {len(codes)} CATALOG templates; N-02 variables {', '.join(n02_vars)}")
            smoke_module.require("link" not in n02_vars, "N-02's template has no link (E76)")

            # 2. Step 1, never edited: E7-T02's N-02 and N-15.
            set_template("N-02", E7T02_N02, False)
            set_template("N-15", E7T02_N15, False)
            before = preview(ids["N-02"])
            print("step 1 before (E7-T02 N-02 stored): preview ca title " + short(before["title"]) + " | body " + short(before["body"]))
            smoke_module.require(before["title"].startswith("Benvingut,") and "enllaç" in before["body"], "the E7-T02 defect is visible before the upgrade")
            restart()
            for code in ("N-02", "N-15"):
                stored = template(code)
                print(f"step 1 after: {code} version {stored['version']}, customized {stored['customized']}, updatedBy {stored['updatedBy']}, "
                      f"languages {sorted(stored['body']['values'])}, body.ca {short(stored['body']['values']['ca'], 200)}")
                smoke_module.require(stored["updatedBy"] == "system:template-upgrade" and sorted(stored["body"]["values"]) == ["ca", "es"]
                                     and not stored["customized"], code + " took the current seed in ca/es")
                detail, saved = put_unchanged(ids[code], 200)
                smoke_module.require(detail["bodyI18n"] == detail["seedDefault"]["bodyI18n"], code + " equals the seed")
                print(f"step 1 after: {code} GET then unchanged PUT -> 200, version {saved['version']}, customized {saved['customized']}")
            after = preview(ids["N-02"])
            print("step 1 after: N-02 preview ca title " + short(after["title"]) + " | body " + short(after["body"]))
            smoke_module.require(after["title"].startswith("Benvinguda") and "enllaç" not in after["body"], "«Benvinguda», no link sentence")

            # 3. Step 1, edited: a round-1 N-02 whose club added a sentence.
            set_template("N-02", ROUND1_N02_EDITED, True)
            refused = put_unchanged(ids["N-02"], 400, "TEMPLATE_UNKNOWN_VARIABLE")[1]
            print("step 1 edited, before: unchanged PUT -> 400 TEMPLATE_UNKNOWN_VARIABLE details " + short(refused["details"]))
            print("step 1 edited, before: preview ca body " + short(preview(ids["N-02"])["body"]))
            restart()
            stored = template("N-02")
            print(f"step 1 edited, after: version {stored['version']}, customized {stored['customized']}, body {short(stored['body']['values'], 300)}")
            smoke_module.require(stored["body"]["values"]["ca"] == "Ja pots entrar a l'app del nostre club. Fins aviat!", "the club's words stay, without the link")
            saved = put_unchanged(ids["N-02"], 200)[1]
            print(f"step 1 edited, after: unchanged PUT -> 200, version {saved['version']}, customized {saved['customized']}; preview ca "
                  + short(preview(ids["N-02"])["body"]))

            # 4. Step 3: the account's own language.
            member = s.mongo('db.members.findOne({clubId:' + club + ',status:"ACTIVE",accountId:{$ne:null}},{_id:1,accountId:1})')
            s.mongo('db.accounts.updateOne({_id:' + json.dumps(member["accountId"]) + '},{$set:{locale:"en"}}).modifiedCount')
            prefs = s.call("GET", f"/api/v1/members/{member['_id']}/notification-preferences", access=session["admin"])
            print(f"step 3: Account.locale en in a ca/es club -> D10 locale {prefs['locale']}, availableLocales {prefs['availableLocales']}")
            smoke_module.require(prefs["locale"] == "en" and prefs["availableLocales"] == ["ca", "es"], "D10 answers the account's own language")

            # 5. Step 5: the seed prints only its rows' variables.
            n08b = s.call("GET", "/api/v1/message-templates/" + ids["N-08b"], access=session["admin"])["seedDefault"]["bodyI18n"]
            n32c = s.call("GET", "/api/v1/message-templates/" + ids["N-32c"], access=session["admin"])["seedDefault"]["smsBodyI18n"]
            print("step 5: N-08b seed body " + short(n08b) + " | N-32c seed SMS " + short(n32c))
            smoke_module.require("class_description" not in json.dumps(n08b) and "{date}" not in json.dumps(n32c), "no variable outside the rows")
            print("PASS E7-T06 local stack")
        finally:
            s.cleanup()


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, OSError) as error:
        print("FAIL: " + str(error), file=sys.stderr)
        sys.exit(1)
