#!/usr/bin/env python3
"""E6-T03 manual verification on a disposable local Compose stack (the working tree's image), with curl.

Reuses bin/e5-smoke's `Smoke` helper (private tokens in stdin, random ports, `compose down -v` at the end). Seeds the
Cànic with its demo, and then, as the seeded staff and member: a 20 MB video/quicktime through the signed upload URL
attached to a new task (201, AttachmentAdded, N-20), a 30 MB file, an .exe, an eleventh attachment and a DOG_DOCUMENT key
(the four refusals), the owner's completion (N-21 to the instructors), the member's note (one MEMBER_NOTE row, N-22), the
D14 page and unread counts of two accounts before and after one read-all, and the TaskReopened / AttachmentRemoved rows.
Round 2 (27-09): the note row and the D14 page show `authorGender`, `TaskCreated` shows its `textExcerpt`, and the D14 page
limit (size 50 → 200, size 200 → 400 INVALID_FILTER). Round 3 (28-09): this run's `MemberNoteChanged` carries the
`author` snapshot and the MEMBER_NOTE row equals it. Round 4 (28-09): rounds of two simultaneous observation saves of one
version (one 200 and one 409 STALE_VERSION each, never a 500). Round 5 (30-09): rounds of two simultaneous keyed
completions of one task (one 200 and one 422 TASK_ALREADY_DONE each) and two simultaneous keyed deletions (one 204 and
one 404), never a 500. Ids are truncated to 8 characters in the output.
"""
import concurrent.futures
import importlib.machinery
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import threading
import uuid

ROOT = Path(__file__).resolve().parents[3]
loader = importlib.machinery.SourceFileLoader("e5smoke", str(ROOT / "bin/e5-smoke"))
spec = importlib.util.spec_from_loader("e5smoke", loader)
e5 = importlib.util.module_from_spec(spec)
loader.exec_module(e5)
e5.LOCAL_IMAGE = "agilityhub-e6-t03-manual:local"
SEEDS = (("club:apply", "seeds/club-canic.yaml"), ("seed:demo", "--club=canic", "--seed=42", e5.WEEK_START))
ROUNDS = 10
short = e5.short


def ids(value):
    """Truncates every UUID of a JSON text to 8 characters and every URL signature (evidence rule)."""
    value = re.sub(r"(signature=)[A-Za-z0-9_%\-]+", r"\1…[truncated]", value)
    return re.sub(r"([0-9a-f]{8})-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", r"\1…", value)


def show(label, value):
    print("  " + label + ": " + ids(json.dumps(value, ensure_ascii=False)), flush=True)


def upload(s, access, purpose, name, mime, size, directory):
    """The signed upload: the grant (upload-url) and the PUT of `size` bytes with the grant's headers only (no bearer)."""
    grant = s.call("POST", "/api/v1/attachments/upload-url", 201, access=access, body=dict(purpose=purpose, fileName=name, mimeType=mime, sizeBytes=size), quiet=True)
    data = Path(directory) / "upload.bin"
    with open(data, "wb") as out:
        out.truncate(size)
    config = "\n".join("header = " + json.dumps(h) for h in [f"{k}: {v}" for k, v in grant["headers"].items()]) + "\nurl = " + json.dumps(s.base + grant["uploadUrl"])
    result = subprocess.run(["curl", "-4", "--silent", "--show-error", "--noproxy", "*", "--max-time", "120", "--request", "PUT", "--data-binary", "@" + str(data),
                             "--output", "/dev/null", "--write-out", "%{http_code}", "--config", "-"], input=config, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    e5.require(result.stdout == "204", f"signed PUT answered {result.stdout}")
    return grant["fileKey"]


def keyed(s, access, method, path, body, tag, barrier):
    """One request with its own `Idempotency-Key` and its own private files (s.call shares one request and one response
    file, so two threads cannot use it); the token stays in stdin, as in s.call. Waits on `barrier` first."""
    payload, response = s.private / f"request-{tag}", s.private / f"response-{tag}"
    headers = ["User-Agent: AgilityHub-E5-Smoke", "Host: " + s.host, "Authorization: Bearer " + access, "Idempotency-Key: " + str(uuid.uuid4())]
    data = []
    if body is not None:
        payload.write_text(json.dumps(body))
        headers.append("Content-Type: application/json")
        data = ["--data-binary", "@" + str(payload)]
    config = "\n".join("header = " + json.dumps(h) for h in headers) + "\nurl = " + json.dumps(s.base + path)
    barrier.wait(timeout=60)
    result = subprocess.run(["curl", "-4", "--silent", "--show-error", "--noproxy", "*", "--max-time", "60", "--request", method, *data,
                             "--output", str(response), "--write-out", "%{http_code}", "--config", "-"], input=config, cwd=ROOT, env=s.env, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    e5.require(result.returncode == 0, f"curl {method} {path} failed (exit {result.returncode})")
    answer = response.read_text() if response.exists() else ""
    return int(result.stdout), (json.loads(answer) if answer else {})


def save_observations(s, access, dog_id, text, version, tag, barrier):
    """Round 4: one `PUT /dogs/{id}/observations` with its own key."""
    return keyed(s, access, "PUT", "/api/v1/dogs/" + dog_id + "/observations", dict(text=text, version=version), "r4-" + tag, barrier)


def together(s, requests, tag):
    """Round 5: the requests `(access, method, path, body)` at once (a thread barrier), each with its own key; their answers in order."""
    barrier = threading.Barrier(len(requests))
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(requests)) as pool:
        calls = [pool.submit(keyed, s, access, method, path, body, f"r5-{tag}-{i}", barrier) for i, (access, method, path, body) in enumerate(requests)]
        return [call.result(timeout=120) for call in calls]


def outcome(answers):
    """Sorted `status` / `status CODE` of the answers."""
    return sorted(f"{status} {body['code']}" if status >= 400 and body.get("code") else str(status) for status, body in answers)


def main():
    (ROOT / "target").mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="e6-t03-manual-", dir=ROOT / "target") as directory:
        s = e5.Smoke(Path(directory), None)
        try:
            s.start(seeds=SEEDS, label="Cànic")
            canic = s.canic
            club = json.dumps(canic)
            # Fixture on this disposable database only: one instructor reads Spanish, so the N-21/N-22 rows show two languages.
            s.mongo('db.accounts.updateOne({email:"instructor.2@example.test"},{$set:{locale:"es"}}).modifiedCount')
            instructor = s.login("instructor@example.test", "clubs-admin")
            admin = s.login("admin@example.test", "clubs-admin")
            member = s.login("member@example.test", "clubs-app")
            dogs = s.call("GET", "/api/v1/me/dogs", access=member)["dogs"]
            dog = next(d for d in dogs if d["status"] == "ACTIVE")
            print(f"Member's dog {short(dog['id'])} {dog['name']} (ACTIVE)", flush=True)

            # 1. A 20 MB video/quicktime through the signed URL, attached to a new task: 201, AttachmentAdded, N-20.
            video = upload(s, instructor, "TASK", "vídeo_balancí.mov", "video/quicktime", 20 * 1024 * 1024, directory)
            print(f"PASS signed PUT of 20 971 520 bytes video/quicktime -> 204 (fileKey {short(video)})", flush=True)
            task = s.call("POST", "/api/v1/tasks", 201, access=instructor, idempotent=True,
                          body=dict(dogId=dog["id"], text="Practiqueu el balancí amb calma: sessions curtes, i sempre acabant amb un èxit.", attachmentIds=[video]))
            show("201 Task", {k: task[k] for k in ("id", "dogId", "state", "createdBy", "attachments", "version")})
            # Round 2: the E6-T04 demo seed has its own tasks, attachments and notes, so the rows are this run's (by aggregate / event id).
            outbox = lambda types, aggregates: s.mongo('db.domain_events.find({clubId:' + club + ',type:{$in:' + json.dumps(types) + '},aggregateId:{$in:' + json.dumps(aggregates)
                                                       + '}}).sort({occurredAt:1,type:1}).toArray().map(e=>({type:e.type,status:e.status,payload:e.payload}))')
            event_id = lambda type, aggregate: s.mongo('db.domain_events.find({clubId:' + club + ',type:' + json.dumps(type) + ',aggregateId:' + json.dumps(aggregate)
                                                       + '}).sort({occurredAt:-1}).limit(1).toArray().map(e=>e._id)')[0]
            rows = e5.wait(lambda: (lambda r: r if len(r) == 2 and all(x["status"] == "PUBLISHED" for x in r) else None)(outbox(["TaskCreated", "AttachmentAdded"], [task["id"], video])),
                           "TaskCreated/AttachmentAdded were not dispatched")
            for row in rows:
                show("outbox", row)
            owner = s.account_of(s.mongo('db.dogs.findOne({_id:' + json.dumps(dog["id"]) + '}).memberId'))
            # Round 2: since E7-T01/E7-T02 a notification is one S11 document per recipient (`recipient.accountId`), one entry per channel in `deliveries`.
            notifications = lambda code, event: s.mongo('db.notifications.find({clubId:' + club + ',code:' + json.dumps(code) + ',eventId:' + json.dumps(event)
                                                        + '}).sort({"recipient.accountId":1}).toArray().flatMap(n=>n.deliveries.map(d=>({code:n.code,channel:d.channel,'
                                                        'status:d.status,account:String(n.recipient.accountId).substring(0,8)+"…",locale:n.locale,variables:n.variables})))')
            n20 = e5.wait(lambda: (lambda r: r if len(r) == 2 else None)(notifications("N-20", event_id("TaskCreated", task["id"]))), "N-20 APP+EMAIL missing")
            for row in n20:
                show("notification", row)
            e5.require(all(r["account"] == short(owner) for r in n20), "N-20 goes to the owner only")

            # 2. The refusals: 30 MB, an .exe, an eleventh attachment, a DOG_DOCUMENT key.
            too_large = s.call("POST", "/api/v1/attachments/upload-url", 400, access=instructor, error="FILE_TOO_LARGE",
                               body=dict(purpose="TASK", fileName="vídeo_llarg.mp4", mimeType="video/mp4", sizeBytes=30 * 1024 * 1024))
            show("400", {k: too_large[k] for k in ("code", "message", "details")})
            exe = s.call("POST", "/api/v1/attachments/upload-url", 400, access=instructor, error="FILE_TYPE_NOT_ALLOWED",
                         body=dict(purpose="TASK", fileName="eina.exe", mimeType="application/x-msdownload", sizeBytes=4096))
            show("400", {k: exe[k] for k in ("code", "message", "details")})
            # A DOG_DOCUMENT key (the admin's own upload) for a task: its purpose is not TASK. Checked while the task has room
            # (at the limit, ATTACHMENT_LIMIT_REACHED answers first).
            document = upload(s, admin, "DOG_DOCUMENT", "cartilla.pdf", "application/pdf", 2048, directory)
            mismatch = s.call("POST", "/api/v1/attachments", 422, access=admin, error="ATTACHMENT_ENTITY_MISMATCH",
                              body=dict(entityType="TASK", entityId=task["id"], fileKey=document, name="cartilla.pdf"))
            show("422", {k: mismatch[k] for k in ("code", "message", "details")})
            for i in range(2, 11):
                key = upload(s, instructor, "TASK", f"foto{i}.pdf", "application/pdf", 2048, directory)
                s.call("POST", "/api/v1/attachments", 201, access=instructor, body=dict(entityType="TASK", entityId=task["id"], fileKey=key, name=f"foto{i}.pdf"), quiet=True)
            print("PASS attachments 2..10 registered (201 each)", flush=True)
            eleventh = upload(s, instructor, "TASK", "foto11.pdf", "application/pdf", 2048, directory)
            limit = s.call("POST", "/api/v1/attachments", 422, access=instructor, error="ATTACHMENT_LIMIT_REACHED",
                           body=dict(entityType="TASK", entityId=task["id"], fileKey=eleventh, name="foto11.pdf"))
            show("422", {k: limit[k] for k in ("code", "message", "details")})

            # 3. The owner completes it: N-21 to every active instructor, in each one's language.
            done = s.call("POST", "/api/v1/tasks/" + task["id"] + "/completion", access=member)
            show("200 Task", {k: done[k] for k in ("state", "doneAt", "doneBy", "version")})
            instructors = s.mongo('db.instructors.find({clubId:' + club + ',active:true}).toArray().map(i=>i.memberId)')
            accounts = [a for a in (s.account_of(m) for m in instructors) if a]
            n21 = e5.wait(lambda: (lambda r: r if len(r) == len(accounts) else None)(notifications("N-21", event_id("TaskCompleted", task["id"]))),
                          f"N-21 rows for the {len(accounts)} instructors missing")
            for row in n21:
                show("notification", row)
            print(f"PASS N-21: {len(n21)} APP rows for the {len(instructors)} active instructors ({len(accounts)} with an account)", flush=True)

            # 4. The member changes the note: one MEMBER_NOTE row, unread for everyone but the author, N-22.
            s.call("PUT", "/api/v1/me/dogs/" + dog["id"] + "/instructor-note", access=member, body=dict(text="A veure si treballem una mica el doble a classe"))
            note = e5.wait(lambda: s.mongo('db.followup_items.find({clubId:' + club + ',kind:"MEMBER_NOTE",dogId:' + json.dumps(dog["id"]) + '}).toArray()'
                                           '.map(r=>({kind:r.kind,authorRole:r.authorRole,authorName:r.authorName,authorGender:r.authorGender,textExcerpt:r.textExcerpt,activityAt:r.activityAt,hidden:r.hidden}))') or None,
                           "No MEMBER_NOTE row")
            e5.require(len(note) == 1, "exactly one MEMBER_NOTE row")
            show("followup_items MEMBER_NOTE", note)
            # Round 3 (review #2): the note's transaction froze its author in MemberNoteChanged, and the row took that snapshot.
            changed = outbox(["MemberNoteChanged"], [dog["id"]])[-1]
            author = changed["payload"].get("author") or {}
            show("outbox MemberNoteChanged", {"status": changed["status"], "dogId": short(changed["payload"]["dogId"]), "memberId": short(changed["payload"]["memberId"]),
                                              "author": {"accountId": short(author.get("accountId") or "-"), "displayName": author.get("displayName"), "gender": author.get("gender")}})
            e5.require(author.get("displayName") == note[0]["authorName"] and author.get("gender") == note[0]["authorGender"], "the row's author is the event's snapshot")
            n22 = e5.wait(lambda: (lambda r: r if len(r) == len(accounts) else None)(notifications("N-22", event_id("MemberNoteChanged", dog["id"]))), "N-22 rows missing")
            for row in n22:
                show("notification", row)

            # 5. D14 for two accounts, the counts before and after one read-all.
            def page(access):
                return [{k: i.get(k) for k in ("kind", "dogName", "authorName", "authorGender", "textExcerpt", "unread")} for i in s.call("GET", "/api/v1/followup?size=20", access=access)["items"]]
            show("GET /followup (instructor) first page", page(instructor))
            show("GET /followup (admin) first page", page(admin))
            before = {n: s.call("GET", "/api/v1/followup/unread-count", access=a)["count"] for n, a in (("instructor", instructor), ("admin", admin))}
            s.call("POST", "/api/v1/followup/read-all", 204, access=admin, idempotent=True)
            after = {n: s.call("GET", "/api/v1/followup/unread-count", access=a)["count"] for n, a in (("instructor", instructor), ("admin", admin))}
            counters = s.call("GET", "/api/v1/dashboard/counters", access=admin)["followUpUnread"]
            print(f"  unread-count before read-all {before}; after the admin's read-all {after}; admin's /dashboard/counters.followUpUnread {counters}", flush=True)
            e5.require(after["admin"] == 0 and after["instructor"] == before["instructor"] and counters == 0, "read-all is per account")

            # 6. TaskReopened and AttachmentRemoved.
            s.call("POST", "/api/v1/tasks/" + task["id"] + "/reopening", access=instructor)
            s.call("DELETE", "/api/v1/attachments/" + video, 204, access=instructor, idempotent=True)
            rows = e5.wait(lambda: (lambda r: r if len(r) == 3 and all(x["status"] == "PUBLISHED" for x in r) else None)(
                               outbox(["TaskCompleted", "TaskReopened", "AttachmentRemoved"], [task["id"], video])),
                           "TaskCompleted/TaskReopened/AttachmentRemoved were not dispatched")
            for row in rows:
                show("outbox", row)
            removed = s.mongo('db.attachments.findOne({_id:' + json.dumps(video) + '},{removedAt:1,removedByAccountId:1,_id:0})')
            show("attachment after DELETE", {"removedAt": removed["removedAt"], "removedByAccountId": short(removed["removedByAccountId"])})

            # 7. Round 2 (review #5, S10 §3): D14 pages hold at most 50 rows; 200 is the list engine's INVALID_FILTER.
            fifty = s.call("GET", "/api/v1/followup?size=50", access=instructor)
            print(f"  GET /followup?size=50 -> 200, size {fifty['size']}, {len(fifty['items'])} items", flush=True)
            refused = s.call("GET", "/api/v1/followup?size=200", 400, access=instructor, error="INVALID_FILTER")
            show("400", {k: refused[k] for k in ("code", "message", "details")})

            # 8. Round 4 (review #1, R-10-12): the instructor and the admin save the observations of one version at the same
            # time, with different keys, ROUNDS times. Every round is one 200 and one 409 STALE_VERSION, never a 500, and
            # one DogUpdated. The interleaving is not controlled here (FollowupIT pins the write conflict); this checks the
            # real stack's answers.
            events_before = len(outbox(["DogUpdated"], [dog["id"]]))
            outcomes = []
            for round_number in range(1, ROUNDS + 1):
                version = s.call("GET", "/api/v1/dogs/" + dog["id"] + "/instructor-card", access=instructor, quiet=True)["observations"]["version"]
                barrier = threading.Barrier(2)
                with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                    saves = [pool.submit(save_observations, s, access, dog["id"], f"Observació {round_number} ({who})", version, f"{round_number}-{who}", barrier)
                             for who, access in (("instructor", instructor), ("admin", admin))]
                    answers = [save.result(timeout=120) for save in saves]
                statuses = sorted(status for status, _ in answers)
                codes = [body.get("code") for status, body in answers if status != 200]
                outcomes.append(statuses)
                e5.require(statuses == [200, 409] and codes == ["STALE_VERSION"], f"round {round_number}: {statuses} {codes}")
            events = e5.wait(lambda: (lambda r: r if len(r) == events_before + ROUNDS else None)(outbox(["DogUpdated"], [dog["id"]])), "DogUpdated rows missing")
            card = s.call("GET", "/api/v1/dogs/" + dog["id"] + "/instructor-card", access=admin, quiet=True)["observations"]
            print(f"  concurrent observation saves: {ROUNDS} rounds, answers {outcomes}; DogUpdated rows +{len(events) - events_before}; card version {card['version']}", flush=True)
            e5.require(card["version"] == version + 1, "one change per round")
            print(f"PASS round 4: {ROUNDS} x (200, 409 STALE_VERSION), no 500", flush=True)

            # 9. Round 5 (review #1, T-10-25, INC-47): the member and the instructor complete one new task at the same time, each
            # with her own Idempotency-Key, ROUNDS times: one 200 and one 422 TASK_ALREADY_DONE per round, never a 500, and one
            # TaskCompleted per task. Then the instructor and the admin delete that task at the same time: one 204 and one 404.
            # The interleaving is not controlled here (FollowupIT holds one request inside its transaction); this checks the
            # real stack's answers.
            created, completions, deletions = [], [], []
            for round_number in range(1, ROUNDS + 1):
                task_id = s.call("POST", "/api/v1/tasks", 201, access=instructor, idempotent=True, quiet=True,
                                 body=dict(dogId=dog["id"], text=f"Tasca simultània {round_number}"))["id"]
                created.append(task_id)
                completion = "/api/v1/tasks/" + task_id + "/completion"
                completions.append(outcome(together(s, [(member, "POST", completion, None), (instructor, "POST", completion, None)], f"c{round_number}")))
                e5.require(completions[-1] == ["200", "422 TASK_ALREADY_DONE"], f"round {round_number}: completions {completions[-1]}")
                deletion = "/api/v1/tasks/" + task_id
                deletions.append(outcome(together(s, [(instructor, "DELETE", deletion, None), (admin, "DELETE", deletion, None)], f"d{round_number}")))
                e5.require(deletions[-1] == ["204", "404 NOT_FOUND"], f"round {round_number}: deletions {deletions[-1]}")
            completed = e5.wait(lambda: (lambda r: r if len(r) == ROUNDS else None)(outbox(["TaskCompleted"], created)), "TaskCompleted rows missing")
            deleted = e5.wait(lambda: (lambda r: r if len(r) == ROUNDS else None)(outbox(["TaskDeleted"], created)), "TaskDeleted rows missing")
            print(f"  simultaneous keyed completions: {ROUNDS} rounds, answers {completions}; TaskCompleted rows {len(completed)} for {len(created)} tasks", flush=True)
            print(f"  simultaneous keyed deletions: {ROUNDS} rounds, answers {deletions}; TaskDeleted rows {len(deleted)}", flush=True)
            print(f"PASS round 5: {ROUNDS} x (200, 422 TASK_ALREADY_DONE) and {ROUNDS} x (204, 404 NOT_FOUND), no 500", flush=True)
            print("PASS E6-T03 manual run", flush=True)
        finally:
            s.cleanup()


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, OSError, subprocess.SubprocessError, StopIteration, KeyError, TypeError, IndexError) as error:
        print("FAIL: " + (str(error) if isinstance(error, AssertionError) else type(error).__name__ + " " + str(error)[:300]), file=sys.stderr)
        sys.exit(1)
