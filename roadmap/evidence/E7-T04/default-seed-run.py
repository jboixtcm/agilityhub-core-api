#!/usr/bin/env python3
"""E7-T04 Verification 2: the plain seed commands on a disposable Compose stack of the working tree.

Runs `club:apply seeds/club-canic.yaml` and `seed:demo --club=canic --seed=42` (no --week-start) twice each through the
image's entry point (`java -jar app.jar --core.command=…`, what bin/core runs), then prints the E7-T04 step 4 «seed diff»:
the preference profiles of the `messaging` section as Mongo holds them (member ordinal, number of contact e-mails and
phones, which address is bounced, the stored `notificationPreferences`, push devices) and the CUSTOM template. Reuses
bin/e5-smoke's stack helpers (random project, private temp dir, `down --volumes` at the end); prints no password, token,
phone number nor e-mail address of a person (the bounced address is a fictional seed literal, printed as its seed key).
"""
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[3]
COMMANDS = (("club:apply", "seeds/club-canic.yaml"), ("seed:demo", "--club=canic", "--seed=42"))
PROFILES = (14, 15, 16, 17, 18)


def load_smoke():
    loader = importlib.machinery.SourceFileLoader("e5smoke", str(ROOT / "bin" / "e5-smoke"))
    spec = importlib.util.spec_from_loader("e5smoke", loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


def main():
    os.chdir(ROOT)
    os.umask(0o077)
    m = load_smoke()
    with tempfile.TemporaryDirectory(prefix="agilityhub-e7-seed-") as directory:
        s = m.Smoke(Path(directory), None)
        s.project = "e7-default-seed-" + secrets.token_hex(6)
        args = s.compose_args
        args[args.index("--project-name") + 1] = s.project
        try:
            s.command(["docker", "info", "--format", "{{.ServerVersion}}"])
            s.started = True
            s.compose("up", "-d", "--wait", "--wait-timeout", "180", "mongo")
            s.compose("build", s.service, timeout=1500)
            print("PASS working tree image built", flush=True)
            m.require(s.mongo("db.clubs.countDocuments()") == 0, "The database must be empty")
            for command in COMMANDS:
                for attempt in (1, 2):
                    output = s.cli(*command)
                    print(f"$ bin/core {' '.join(command)}   # run {attempt}", flush=True)
                    for line in output.strip().splitlines():
                        if "changes" in line or line.startswith("{"):
                            print("  " + line, flush=True)
                    if attempt == 2:
                        m.require("0 changes (" in output and not re.search(r"\n[1-9][0-9]* changes \(", "\n" + output), "The second run changed something")
                        print(f"PASS {' '.join(command)} run 2: 0 changes", flush=True)
            club = s.mongo('db.clubs.findOne({slug:"canic"})._id')
            run = s.mongo('db.demo_seed_runs.findOne({_id:' + json.dumps(club + ":planning") + '},{counts:1,_id:0})')["counts"]
            counts = {k: run[k] for k in ("messagingContacts", "messagingProfiles", "messagingBounces", "pushSubscriptions", "customTemplates")}
            print(f"Counts (demo planning run): {json.dumps(counts)}", flush=True)
            print("Seed diff of the preference profiles (seeds/demo-canic.yaml `messaging`; ordinal 14 = member.10@):", flush=True)
            for ordinal in PROFILES:
                # The demo numbers its members ordinal + 1 (seeds/demo-canic.yaml `accountEmails`: member.10@ = ordinal 14 = number 15).
                member = s.mongo('(()=>{const m=db.members.findOne({clubId:' + json.dumps(club) + ',memberNumber:' + str(ordinal + 1) + '});'
                                 'return {number:m.memberNumber,emails:(m.contactEmails||[]).length,bounced:(m.contactEmails||[]).filter(e=>e.bounced===true)'
                                 '.map(e=>e.email.endsWith("antic@example.test")?"demo.canic.antic@ (seed)":"other"),phones:(m.phones||[]).length,'
                                 'preferences:m.notificationPreferences||{},push:db.push_subscriptions.countDocuments({accountId:m.accountId,status:"ACTIVE"})}})()')
                prefs = {k: v for k, v in member["preferences"].items() if k in ("emailByCategory", "reminderMinutesBefore", "pushClubNews")}
                print(f"  ordinal {ordinal} (member number {member['number']}): contact e-mails {member['emails']} (bounced {member['bounced']}), "
                      f"phones {member['phones']}, push devices {member['push']}, preferences {json.dumps(prefs, sort_keys=True)}", flush=True)
            login = s.mongo('db.members.findOne({clubId:' + json.dumps(club) + ',memberNumber:15}).accountId==db.accounts.findOne({email:"member.10@example.test"})._id')
            m.require(login, "Member number 15 is member.10@")
            custom = s.mongo('db.message_templates.find({clubId:' + json.dumps(club) + ',kind:"CUSTOM"}).toArray().map(t=>({category:t.category,status:t.status,'
                             'title:t.title.values}))')
            print(f"CUSTOM templates: {json.dumps(custom, ensure_ascii=False)}", flush=True)
            m.require(counts == {"messagingContacts": 2, "messagingProfiles": 3, "messagingBounces": 1, "pushSubscriptions": 1, "customTemplates": 1},
                      "The messaging section was not applied as written")
            print("PASS the plain seed:demo applies the E7-T04 preference profiles and the CUSTOM template; the second runs report 0 changes", flush=True)
        finally:
            s.cleanup()


if __name__ == "__main__":
    try:
        main()
    except Exception as error:  # noqa: BLE001 — subprocess errors can carry private arguments: print only the type and a short text
        print("FAIL: " + (str(error) if isinstance(error, AssertionError) else type(error).__name__ + " " + str(error)[:160]), file=sys.stderr)
        sys.exit(1)
