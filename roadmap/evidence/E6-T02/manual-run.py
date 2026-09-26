#!/usr/bin/env python3
"""E6-T02 manual verification on a disposable local Compose stack (the working tree's image), with curl.

Reuses bin/e5-smoke's `Smoke` helper (private tokens in stdin, random ports, `compose down -v` at the end). Seeds the
Cànic with its demo scenario, picks a seeded class that is full and has a waiting list, moves the test clock to four
hours before it and, as the seeded instructor: GET the sheet, PUT PRESENT + NOTIFIED, shows the released seat, the
outbox rows and the N-05 / N-15 notification rows, a stale PUT's 409, and downloads the D12 week PDF.
"""
import datetime
import importlib.machinery
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import uuid

ROOT = Path(__file__).resolve().parents[3]
EVIDENCE = ROOT / "roadmap/evidence/E6-T02"
loader = importlib.machinery.SourceFileLoader("e5smoke", str(ROOT / "bin/e5-smoke"))
spec = importlib.util.spec_from_loader("e5smoke", loader)
e5 = importlib.util.module_from_spec(spec)
loader.exec_module(e5)
e5.LOCAL_IMAGE = "agilityhub-e6-t02-manual:local"
SEEDS = (("club:apply", "seeds/club-canic.yaml"), ("seed:demo", "--club=canic", "--seed=42", e5.WEEK_START))
short = e5.short


def ids(value):
    """Truncates every UUID of a JSON text to 8 characters (evidence rule)."""
    import re
    return re.sub(r"([0-9a-f]{8})-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", r"\1…", value)


def main():
    (ROOT / "target").mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="e6-t02-manual-", dir=ROOT / "target") as directory:
        s = e5.Smoke(Path(directory), None)
        try:
            s.start(seeds=SEEDS, label="Cànic")
            canic = s.canic
            classes = s.mongo('db.class_sessions.find({clubId:' + json.dumps(canic) + ',state:"ACTIVE","counters.waiting":{$gte:1},'
                              '$expr:{$gte:["$counters.booked","$capacity"]}}).sort({startsAt:1}).toArray()'
                              '.map(c=>({id:c._id,startsAt:c.startsAt,date:c.date,startTime:c.startTime,booked:c.counters.booked,waiting:c.counters.waiting,capacity:c.capacity}))')
            e5.require(classes, "No full seeded class with a waiting list")
            target = classes[0]
            starts = datetime.datetime.fromisoformat(target["startsAt"].replace("Z", "+00:00"))
            print(f"Target class {short(target['id'])} {target['date']} {target['startTime']} (Madrid) capacity {target['capacity']}, "
                  f"booked {target['booked']}, waiting {target['waiting']}", flush=True)
            s.set_clock(starts - datetime.timedelta(hours=4, minutes=5), "four hours (and 5 minutes) before the class: in time")
            instructor = s.login("instructor@example.test", "clubs-admin")
            path = "/api/v1/class-sessions/" + target["id"] + "/attendance"
            sheet = s.call("GET", path, access=instructor)
            rows = [r for r in sheet["rows"] if r["state"] == "PENDING"]
            e5.require(len(rows) >= 2, "The class needs two live rows")
            present, notified = rows[0], rows[1]
            print(f"  sheet: version {sheet['sheet']['version']}, canMarkPresence {sheet['sheet']['canMarkPresence']}, canMarkNotice "
                  f"{sheet['sheet']['canMarkNotice']}, editableUntil {sheet['sheet']['editableUntil']}; classSession booked "
                  f"{sheet['classSession']['booked']}/{sheet['classSession']['capacity']}, waiting {sheet['classSession'].get('waiting')}; "
                  f"{len(sheet['rows'])} rows; waitlist mode {sheet['waitlist']['mode']} with {len(sheet['waitlist']['entries'])} entries", flush=True)
            before = sheet["sheet"]["version"]
            body = dict(version=before, items=[dict(bookingId=present["bookingId"], state="PRESENT"), dict(bookingId=notified["bookingId"], state="NOTIFIED")])
            saved = s.call("PUT", path, access=instructor, body=body, idempotent=True)
            row = next(r for r in saved["rows"] if r["bookingId"] == notified["bookingId"])
            print(f"  PUT applied {[short(a) for a in saved['applied']]}; version {before} → {saved['sheet']['version']}; "
                  f"notice {json.dumps(row['notice'])}", flush=True)
            after = s.call("GET", path, access=instructor)
            print(f"  released seat: classSession booked {sheet['classSession']['booked']} → {after['classSession']['booked']} of "
                  f"{after['classSession']['capacity']}; booking {short(notified['bookingId'])} is "
                  + s.mongo('db.bookings.findOne({_id:' + json.dumps(notified["bookingId"]) + '}).state'), flush=True)
            since = "new Date(" + json.dumps(s.utc(starts - datetime.timedelta(hours=4, minutes=6))) + ")"
            outbox = lambda: s.mongo('db.domain_events.find({clubId:' + json.dumps(canic) + ',type:{$in:["AttendanceMarked","BookingCancelled","SeatReleased",'
                                     '"WaitlistNotified"]},occurredAt:{$gte:' + since + '}}).sort({occurredAt:1,type:1}).toArray()'
                                     '.map(e=>({type:e.type,status:e.status,payload:e.payload}))')
            events = e5.wait(lambda: (lambda rows: rows if any(r["type"] == "WaitlistNotified" and r["status"] == "PUBLISHED" for r in rows) else None)(outbox()),
                             "WaitlistNotified was not dispatched")
            print("  outbox rows after the save (dispatched by the scheduler):", flush=True)
            for event in events:
                print("    " + ids(json.dumps(event, ensure_ascii=False)), flush=True)
            member_account = s.account_of(s.mongo('db.bookings.findOne({_id:' + json.dumps(notified["bookingId"]) + '}).memberId'))
            waiting_accounts = s.mongo('db.waitlist_entries.find({classSessionId:' + json.dumps(target["id"]) + ',state:"NOTIFIED"}).toArray().map(e=>e.accountId)')
            n05 = e5.wait(lambda: s.mongo('db.notifications.find({clubId:' + json.dumps(canic) + ',code:"N-05",accountId:' + json.dumps(member_account)
                                          + '}).toArray().map(n=>({code:n.code,channel:n.channel,status:n.status}))') or None, "N-05 missing")
            n15 = e5.wait(lambda: s.mongo('db.notifications.find({clubId:' + json.dumps(canic) + ',code:"N-15",accountId:{$in:' + json.dumps(waiting_accounts)
                                          + '}}).toArray().map(n=>({code:n.code,channel:n.channel,status:n.status,account:n.accountId.substring(0,8)+"…"}))') or None,
                          "N-15 missing")
            print(f"  notifications N-05 (member {short(member_account)}): {json.dumps(n05)}", flush=True)
            print(f"  notifications N-15 ({len(waiting_accounts)} waiting entries now NOTIFIED): {json.dumps(n15)}", flush=True)
            e5.require(all(n["channel"] != "SMS" for n in n05), "N-05 must have no SMS row")
            stale = s.call("PUT", path, 409, access=instructor, body=dict(version=before, items=[dict(bookingId=present["bookingId"], state="NO_SHOW")]),
                           idempotent=True, error="STALE_VERSION")
            current = stale["details"]["current"]
            print("  409 body: " + ids(json.dumps({"code": stale["code"], "message": stale["message"], "traceId": stale["traceId"],
                                                   "details": {"current": {"sheet": current["sheet"], "classSession": current["classSession"],
                                                                           "rows": [{k: r.get(k) for k in ("bookingId", "dogName", "state", "final", "notice")}
                                                                                    for r in current["rows"]]}}}, ensure_ascii=False)), flush=True)
            pdf = EVIDENCE / "agenda.pdf"
            config = "\n".join("header = " + json.dumps(h) for h in ["Authorization: Bearer " + instructor, "Host: " + s.host, "Accept-Language: ca"]) \
                + "\nurl = " + json.dumps(s.base + "/api/v1/instructor/week/export?format=pdf&date=" + target["date"])
            result = subprocess.run(["curl", "-4", "--silent", "--show-error", "--noproxy", "*", "--max-time", "60", "--output", str(pdf),
                                     "--write-out", "%{http_code} %{content_type}", "--dump-header", str(Path(directory) / "headers"), "--config", "-"],
                                    input=config, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            status, content_type = result.stdout.split(" ", 1)
            disposition = next((line.strip() for line in (Path(directory) / "headers").read_text().splitlines() if line.lower().startswith("content-disposition")), "")
            print(f"curl GET /api/v1/instructor/week/export?format=pdf&date={target['date']} -> {status} {content_type}; {disposition}; "
                  f"{pdf.stat().st_size} bytes saved to roadmap/evidence/E6-T02/agenda.pdf", flush=True)
            e5.require(status == "200" and content_type == "application/pdf", "The PDF export failed")
            print("PASS E6-T02 manual run", flush=True)
        finally:
            s.cleanup()


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, OSError, subprocess.SubprocessError, StopIteration, KeyError, TypeError, IndexError) as error:
        print("FAIL: " + (str(error) if isinstance(error, AssertionError) else type(error).__name__ + " " + str(error)[:300]), file=sys.stderr)
        sys.exit(1)
