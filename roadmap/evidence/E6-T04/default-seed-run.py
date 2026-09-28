#!/usr/bin/env python3
"""E6-T04 round 2 (review #5): the Verification's plain seed commands on a disposable Compose stack of the working tree.

Runs `club:apply seeds/club-canic.yaml` and `seed:demo --club=canic --seed=42` (no --week-start) twice each through the
image's entry point (`java -jar app.jar --core.command=…`, what bin/core runs), on the real date, then reads the anchor and
the E6 fixture of R-10-02's example from Mongo. Reuses bin/e5-smoke's stack helpers (random project, private temp dir,
`down --volumes` at the end); prints no password, token or full id.
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
    with tempfile.TemporaryDirectory(prefix="agilityhub-e6-seed-") as directory:
        s = m.Smoke(Path(directory), None)
        s.project = "e6-default-seed-" + secrets.token_hex(6)
        args = s.compose_args
        args[args.index("--project-name") + 1] = s.project
        try:
            s.command(["docker", "info", "--format", "{{.ServerVersion}}"])
            s.started = True
            s.compose("up", "-d", "--wait", "--wait-timeout", "180", "mongo")
            s.compose("build", s.service, timeout=1500)
            print("PASS working tree image built", flush=True)
            m.require(s.mongo("db.clubs.countDocuments()") == 0, "The database must be empty")
            today = s.mongo("new Date().toISOString().slice(0,10)")
            print(f"Run date (UTC, the container's clock): {today}", flush=True)
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
            run = s.mongo('db.demo_seed_runs.findOne({_id:' + json.dumps(club + ":planning") + '},{weekStart:1,counts:1,_id:0})')
            week = run["weekStart"]
            counts = {k: run["counts"][k] for k in ("scenarioClassBookings", "attendanceHistoryClasses", "attendanceBookings", "attendanceWaitlist",
                                                    "attendanceMarks", "attendanceNoShowBatches", "followupTasks", "followupNotes", "trainingHistoryBookings")}
            ring = s.mongo('db.rings.findOne({clubId:' + json.dumps(club) + ',shortName:"CEN"})._id')
            sheet = s.mongo('db.class_sessions.findOne({clubId:' + json.dumps(club) + ',date:' + json.dumps(week) + ',startTime:"08:30",ringId:'
                            + json.dumps(ring) + '},{state:1,capacity:1,counters:1,_id:0})')
            print(f"Anchor (demo_seed_runs.weekStart): {week}", flush=True)
            print(f"Counts: {json.dumps(counts)}", flush=True)
            print(f"Sheet class {week} 08:30 CEN: {json.dumps(sheet)}", flush=True)
            m.require(counts["attendanceBookings"] == 34 and counts["attendanceWaitlist"] == 1 and counts["attendanceHistoryClasses"] == 10,
                      "The E6 fixture is missing")
            m.require(sheet["counters"] == {"booked": 4, "waiting": 1} and sheet["capacity"] == 5, "The sheet class is not 4 booked + 1 waiting of 5")
            print("PASS the plain seed:demo seeds the E6 fixture of R-10-02's example on the first Monday on or after the run date; "
                  "the second runs report 0 changes", flush=True)
        finally:
            s.cleanup()


if __name__ == "__main__":
    try:
        main()
    except Exception as error:  # noqa: BLE001 — subprocess errors can carry private arguments: print only the type and a short text
        print("FAIL: " + (str(error) if isinstance(error, AssertionError) else type(error).__name__ + " " + str(error)[:160]), file=sys.stderr)
        sys.exit(1)
