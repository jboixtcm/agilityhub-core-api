#!/usr/bin/env python3
"""E7-T02 local-stack verification on a disposable Compose stack (the working tree's image, profile `local`), with curl.

Reuses bin/e5-smoke's `Smoke` helper (tokens in stdin, random ports, `compose down -v` at the end). Seeds the Cànic
(`club:apply seeds/club-canic.yaml`, `seed:demo --club=canic --seed=42`), then, as the seeded admin, cancels a seeded
future class with registrants (`POST /api/v1/class-sessions/{id}/cancellation`) and reads, with `mongosh --quiet --eval`,
one N-08a `notifications` document per registrant: APP DELIVERED, EMAIL SENT (the local mailbox holds the file) and SMS SENT
through `LogSmsSender` (provider reference `log-…`). R-14-18: no address, phone or message body is printed; ids and
provider references are truncated to 8 characters.
"""
import importlib.machinery
import importlib.util
import json
from pathlib import Path
import re
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[3]
loader = importlib.machinery.SourceFileLoader("e5smoke", str(ROOT / "bin/e5-smoke"))
spec = importlib.util.spec_from_loader("e5smoke", loader)
e5 = importlib.util.module_from_spec(spec)
loader.exec_module(e5)
e5.LOCAL_IMAGE = "agilityhub-e7-t02-manual:local"
SEEDS = (("club:apply", "seeds/club-canic.yaml"), ("seed:demo", "--club=canic", "--seed=42", e5.WEEK_START))
short = e5.short


def main():
    (ROOT / "target").mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="e7-t02-manual-", dir=ROOT / "target") as directory:
        s = e5.Smoke(Path(directory), None)
        try:
            s.start(seeds=SEEDS, label="Cànic")
            club = json.dumps(s.canic)
            modules = s.mongo('db.clubs.findOne({_id:' + club + '}).modules')
            print("Cànic modules: " + ", ".join(sorted(modules)), flush=True)
            e5.require("SMS" in modules and "PUSH" in modules, "The Cànic preset has SMS and PUSH")
            # The seeded ACTIVE class (in the future) with the most ACTIVE bookings.
            pick = s.mongo('db.bookings.aggregate([{$match:{clubId:' + club + ',state:"ACTIVE"}},{$group:{_id:"$classSessionId",n:{$sum:1}}},'
                           '{$lookup:{from:"class_sessions",localField:"_id",foreignField:"_id",as:"c"}},{$unwind:"$c"},'
                           '{$match:{"c.state":"ACTIVE","c.startsAt":{$gt:new Date()}}},{$sort:{n:-1,_id:1}},{$limit:1},'
                           '{$project:{n:1,date:"$c.date",startTime:"$c.startTime"}}]).toArray()[0]')
            class_id = pick["_id"]
            print(f"Seeded class {short(class_id)} on {pick['date']} {pick['startTime']} with {pick['n']} ACTIVE bookings", flush=True)
            admin = s.login("admin@example.test", "clubs-admin")
            s.call("POST", f"/api/v1/class-sessions/{class_id}/cancellation", 200, access=admin, idempotent=True,
                   body=dict(reason="CLUB_MANUAL", adminText="La pista està mullada per la pluja: us esperem la setmana vinent."))
            query = 'db.notifications.find({clubId:' + club + ',code:"N-08a","subject.classSessionId":' + json.dumps(class_id) + '})'
            # The scheduler dispatches the outbox and the engine sends right after its commit (and the 5-second poll retries).
            e5.wait(lambda: (lambda docs: docs if docs and all(d["status"] != "QUEUED" for n in docs for d in n["deliveries"]) else None)(
                s.mongo(query + '.toArray().map(n=>({deliveries:n.deliveries.map(d=>({status:d.status}))}))')),
                "The N-08a deliveries were not settled", timeout=180)
            rows = s.mongo(query + '.sort({audience:1,dedupKey:1}).toArray().map(n=>({id:n._id,audience:n.audience,member:n.recipient.memberId,'
                           'locale:n.locale,dog:n.subject.dogId,title:n.title,deliveries:n.deliveries.map(d=>({channel:d.channel,status:d.status,'
                           'attempts:d.attempts,ref:d.providerRef||null}))}))')
            print(f"mongosh --quiet --eval '{query.replace(club, '<canic>').replace(json.dumps(class_id), '<class>')}': {len(rows)} documents", flush=True)
            for n in rows:
                deliveries = ", ".join(f"{d['channel']} {d['status']}" + (f" ref {short(d['ref'])}" if d["ref"] else "") for d in n["deliveries"])
                print(f"  {n['audience']:<11} member {short(n.get('member'))} dog {short(n.get('dog'))} {n['locale']} «{n['title']}» [{deliveries}]", flush=True)
            members = [n for n in rows if n["audience"] == "MEMBER"]
            e5.require(len(members) >= pick["n"], "One MEMBER notification per registrant (and per waiting dog)")
            for n in members:
                channels = {d["channel"]: d for d in n["deliveries"]}
                e5.require(any(d["channel"] == "APP" and d["status"] == "DELIVERED" for d in n["deliveries"]), "APP DELIVERED")
            sent_mail = [d for n in members for d in n["deliveries"] if d["channel"] == "EMAIL" and d["status"] == "SENT"]
            sent_sms = [d for n in members for d in n["deliveries"] if d["channel"] == "SMS" and d["status"] == "SENT"]
            e5.require(sent_mail and sent_sms and all(d["ref"].startswith("log-") for d in sent_sms), "EMAIL SENT and SMS SENT through LogSmsSender")
            with_all = [n for n in members if {("APP", "DELIVERED"), ("EMAIL", "SENT"), ("SMS", "SENT")} <= {(d["channel"], d["status"]) for d in n["deliveries"]}]
            s.record("N-08a per registrant", f"{len(members)} MEMBER documents", f"{len(with_all)} with APP DELIVERED + EMAIL SENT + SMS SENT; "
                     f"{len(sent_mail)} e-mails, {len(sent_sms)} SMS (LogSmsSender)")
            # The local mailbox (LogEmailSender, MAIL_LOCAL_DIRECTORY): one JSON file per accepted e-mail, tagged with its notificationId.
            mailed = [n["id"] for n in members if any(d["channel"] == "EMAIL" and d["status"] == "SENT" for d in n["deliveries"])]
            pattern = " ".join("-e " + i for i in mailed)
            listing = s.compose("exec", "-T", s.service, "sh", "-c", f"grep -l -F {pattern} /app/mailbox/*.json | wc -l")
            captured = int(listing.strip().splitlines()[-1])
            s.record("local mailbox", f"{captured} files carry the N-08a notification ids", f"{len(sent_mail)} EMAIL deliveries SENT")
            e5.require(captured >= len(mailed), "Every N-08a notification with a sent e-mail is in the local mailbox")
            outbox = s.mongo('db.domain_events.aggregate([{$match:{clubId:' + club + ',type:{$in:["NotificationQueued","NotificationSent","NotificationFailed"]},'
                             'aggregateId:{$in:' + query + '.toArray().map(n=>n._id)}}},{$group:{_id:"$type",n:{$sum:1}}},{$sort:{_id:1}}]).toArray()')
            s.record("outbox", ", ".join(f"{o['_id']} {o['n']}" for o in outbox))
        finally:
            s.cleanup()
    print("E7-T02 local stack: OK", flush=True)


if __name__ == "__main__":
    try:
        main()
    except AssertionError as failure:
        print("FAIL " + re.sub(r"[\w.+-]+@[\w.-]+", "<address>", str(failure)), flush=True)
        sys.exit(1)
