#!/usr/bin/env python3
"""Roadmap checker / renderer (stdlib only).

Usage:
  python3 roadmap/tools/check.py                 # validate, exit 1 on errors
  python3 roadmap/tools/check.py --render        # validate + rewrite roadmap/STATUS.md
  python3 roadmap/tools/check.py --set ID STATUS # change a task status (+ updated date), then render
  python3 roadmap/tools/check.py --next          # print the task the executor should take now
  python3 roadmap/tools/check.py --field ID KEY VALUE   # set a front matter field (branch, pr), then render

Task files live in roadmap/tasks/<ID>.md with a front matter block:
---
id: E0-T01
title: ...
stage: E0
repo: agilityhub-core-api
thread: A
order: 10
status: ready
depends_on: [E0-T00, external:jordi-repos]
branch:
pr:
updated: 2026-09-05
---
"""
import sys, re, os, datetime, io

STATES = ["not_open", "ready", "in_progress", "awaiting_verification", "changes_requested", "blocked", "verified"]
MAX_TASK_BYTES = 120 * 1024  # evidence rule: long outputs live in roadmap/evidence/<ID>/
ICON = {"not_open": "—", "ready": "☐", "in_progress": "▶", "awaiting_verification": "🔎",
        "changes_requested": "🔁", "blocked": "⛔", "verified": "✅"}
NEXT = {"not_open": "organizer opens it", "ready": "executor: start", "in_progress": "executor: resume + report",
        "awaiting_verification": "organizer: verify", "changes_requested": "executor: apply corrections",
        "blocked": "see MESSAGES.md", "verified": "—"}
REQUIRED = ["id", "title", "stage", "repo", "order", "status", "depends_on", "updated"]

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # roadmap/
TASKS = os.path.join(ROOT, "tasks")
STATUS_MD = os.path.join(ROOT, "STATUS.md")


def parse_front_matter(text):
    m = re.match(r"^---\n(.*?)\n---\n", text, re.S)
    if not m:
        return None, text
    fm = {}
    for line in m.group(1).splitlines():
        if not line.strip() or line.strip().startswith("#"):
            continue
        if ":" not in line:
            continue
        k, v = line.split(":", 1)
        k, v = k.strip(), v.strip()
        if v.startswith("[") and v.endswith("]"):
            inner = v[1:-1].strip()
            v = [x.strip() for x in inner.split(",") if x.strip()] if inner else []
        fm[k] = v
    return fm, text[m.end():]


def load_tasks():
    tasks = {}
    errors = []
    if not os.path.isdir(TASKS):
        return tasks, ["missing folder roadmap/tasks"]
    for name in sorted(os.listdir(TASKS)):
        if not name.endswith(".md"):
            continue
        path = os.path.join(TASKS, name)
        text = open(path, encoding="utf-8").read()
        fm, body = parse_front_matter(text)
        if fm is None:
            errors.append(f"{name}: no front matter")
            continue
        for k in REQUIRED:
            if k not in fm:
                errors.append(f"{name}: missing front matter key '{k}'")
        tid = fm.get("id", "")
        if tid != name[:-3]:
            errors.append(f"{name}: id '{tid}' does not match file name")
        if fm.get("status") not in STATES:
            errors.append(f"{name}: invalid status '{fm.get('status')}' (allowed: {', '.join(STATES)})")
        if not isinstance(fm.get("depends_on", []), list):
            fm["depends_on"] = [fm["depends_on"]] if fm.get("depends_on") else []
        try:
            fm["order"] = int(fm.get("order", 0))
        except ValueError:
            errors.append(f"{name}: order must be an integer")
            fm["order"] = 0
        fm["_body"] = body
        fm["_path"] = path
        fm["_text"] = text
        tasks[tid] = fm
    return tasks, errors


def section(body, title):
    m = re.search(r"^## " + re.escape(title) + r"\s*\n(.*?)(?=^## |\Z)", body, re.S | re.M)
    return m.group(1) if m else ""


def validate(tasks):
    errors, warnings = [], []
    for tid, t in sorted(tasks.items(), key=lambda kv: (kv[1]["stage"], kv[1]["order"])):
        st = t["status"]
        deps = t["depends_on"]
        for d in deps:
            if d.startswith("external:"):
                continue
            if d not in tasks:
                errors.append(f"{tid}: depends_on '{d}' does not exist")
        real = [d for d in deps if not d.startswith("external:") and d in tasks]
        if st in ("in_progress", "awaiting_verification"):
            for d in real:
                ds = tasks[d]["status"]
                if ds == "changes_requested":
                    warnings.append(f"{tid}: dependency {d} is changes_requested — rebase may be needed")
                elif ds not in ("verified", "awaiting_verification"):
                    errors.append(f"{tid}: cannot be {st} while dependency {d} is {ds}")
        if st == "ready":
            for d in real:
                if tasks[d]["status"] in ("not_open", "blocked"):
                    warnings.append(f"{tid}: ready but dependency {d} is {tasks[d]['status']}")
        rep = section(t["_body"], "Executor report")
        ver = section(t["_body"], "Organizer verification")
        if st == "awaiting_verification" and ("_(not started)_" in rep or len(rep.strip()) < 200):
            errors.append(f"{tid}: awaiting_verification but the Executor report is empty (evidence required)")
        if st == "awaiting_verification" and len(t["_text"].encode("utf-8")) > MAX_TASK_BYTES:
            errors.append(f"{tid}: task file is {len(t['_text'].encode('utf-8')) // 1024} KB (max {MAX_TASK_BYTES // 1024} KB) — "
                          f"move long outputs to roadmap/evidence/{tid}/NN-<name>.log and keep the last 40 lines of each in the report")
        if st == "verified" and "Result: verified" not in ver:
            errors.append(f"{tid}: status verified but the Organizer verification section has no 'Result: verified'")
        if st == "changes_requested" and "Result: changes_requested" not in ver:
            errors.append(f"{tid}: status changes_requested but no 'Result: changes_requested' with a numbered list")
        if not re.match(r"\d{4}-\d{2}-\d{2}$", str(t.get("updated", ""))):
            errors.append(f"{tid}: 'updated' must be YYYY-MM-DD")
    return errors, warnings


def next_for_executor(tasks):
    ordered = sorted(tasks.values(), key=lambda t: (t["stage"], t["order"]))
    for t in ordered:
        if t["status"] == "changes_requested":
            return t
    for t in ordered:          # a previous session that did not finish: resume it
        if t["status"] == "in_progress":
            return t
    for t in ordered:
        if t["status"] != "ready":
            continue
        ok = True
        for d in t["depends_on"]:
            if d.startswith("external:"):
                continue
            if tasks.get(d, {}).get("status") not in ("verified", "awaiting_verification"):
                ok = False
        if ok:
            return t
    return None


def render(tasks, errors, warnings):
    out = io.StringIO()
    today = datetime.date.today().isoformat()
    out.write("# STATUS — generated by `roadmap/tools/check.py --render` (do not edit by hand)\n\n")
    out.write(f"Rendered: {today} · Tasks: {len(tasks)} · " + " · ".join(
        f"{ICON[s]} {sum(1 for t in tasks.values() if t['status']==s)} {s}" for s in STATES) + "\n\n")
    nxt = next_for_executor(tasks)
    out.write("**Next task for the executor**: " + (f"`{nxt['id']}` — {nxt['title']}" if nxt else "none (wait for the organizer)") + "\n\n")
    if errors:
        out.write("**Validation errors** (fix before committing):\n\n" + "".join(f"- {e}\n" for e in errors) + "\n")
    if warnings:
        out.write("**Warnings**:\n\n" + "".join(f"- {w}\n" for w in warnings) + "\n")
    stages = sorted({t["stage"] for t in tasks.values()})
    for s in stages:
        out.write(f"## {s}\n\n| ID | Title | Thread | Status | Depends on | Branch / PR | Updated | Next action |\n|---|---|---|---|---|---|---|---|\n")
        for t in sorted([t for t in tasks.values() if t["stage"] == s], key=lambda t: t["order"]):
            deps = ", ".join(t["depends_on"]) or "—"
            br = t.get("branch") or ""
            pr = t.get("pr") or ""
            brpr = " / ".join(x for x in (br, pr) if x) or "—"
            out.write(f"| [{t['id']}](tasks/{t['id']}.md) | {t['title']} | {t.get('thread','')} | {ICON[t['status']]} `{t['status']}` | {deps} | {brpr} | {t['updated']} | {NEXT[t['status']]} |\n")
        out.write("\n")
    return out.getvalue()


def set_status(tasks, tid, status):
    if tid not in tasks:
        print(f"unknown task {tid}"); sys.exit(2)
    if status not in STATES:
        print(f"invalid status {status}"); sys.exit(2)
    t = tasks[tid]
    text = t["_text"]
    text = re.sub(r"^status:.*$", f"status: {status}", text, count=1, flags=re.M)
    text = re.sub(r"^updated:.*$", f"updated: {datetime.date.today().isoformat()}", text, count=1, flags=re.M)
    open(t["_path"], "w", encoding="utf-8").write(text)
    print(f"{tid}: status -> {status}")


def set_field(tasks, tid, key, value):
    if tid not in tasks:
        print(f"unknown task {tid}"); sys.exit(2)
    if key in ("status", "id"):
        print("use --set for status; id is immutable"); sys.exit(2)
    t = tasks[tid]
    text = t["_text"]
    if re.search(r"^" + re.escape(key) + r":.*$", text, flags=re.M):
        text = re.sub(r"^" + re.escape(key) + r":.*$", f"{key}: {value}", text, count=1, flags=re.M)
    else:
        text = text.replace("\n---\n", f"\n{key}: {value}\n---\n", 1)
    text = re.sub(r"^updated:.*$", f"updated: {datetime.date.today().isoformat()}", text, count=1, flags=re.M)
    open(t["_path"], "w", encoding="utf-8").write(text)
    print(f"{tid}: {key} -> {value}")


def main(argv):
    tasks, errors = load_tasks()
    if "--field" in argv:
        i = argv.index("--field")
        set_field(tasks, argv[i + 1], argv[i + 2], argv[i + 3])
        tasks, errors = load_tasks()
        argv = argv + ["--render"]
    if "--set" in argv:
        i = argv.index("--set")
        tid, new_status = argv[i + 1], argv[i + 2]
        before_errors = set(validate(tasks)[0])
        previous_text = tasks[tid]["_text"] if tid in tasks else None
        set_status(tasks, tid, new_status)
        tasks, errors = load_tasks()
        introduced = [e for e in validate(tasks)[0] if e not in before_errors and e.startswith(tid + ":")]
        if introduced:
            open(tasks[tid]["_path"], "w", encoding="utf-8").write(previous_text)
            print(f"REVERTED {tid}: the change is not allowed —")
            for e in introduced:
                print("  ERROR:", e)
            sys.exit(1)
        argv = argv + ["--render"]
    e2, warnings = validate(tasks)
    errors += e2
    if "--next" in argv:
        n = next_for_executor(tasks)
        print(n["id"] if n else "none")
    if "--render" in argv:
        open(STATUS_MD, "w", encoding="utf-8").write(render(tasks, errors, warnings))
        print(f"STATUS.md rendered ({len(tasks)} tasks)")
    for w in warnings:
        print("WARN:", w)
    for e in errors:
        print("ERROR:", e)
    if errors:
        sys.exit(1)
    if "--render" not in argv and "--next" not in argv:
        print(f"OK — {len(tasks)} tasks valid")


if __name__ == "__main__":
    main(sys.argv[1:])
