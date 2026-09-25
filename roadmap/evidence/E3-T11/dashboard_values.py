#!/usr/bin/env python3
"""E3-T11 step 7: GET /dashboard on the seeded Cànic, printing the values the gate line quotes.

Loads bin/e3-smoke unchanged and uses only its stack setup (`Smoke.start()`: a disposable Compose
project, the working tree image, club:apply seeds/club-canic.yaml and seed:demo --club=canic --seed=42,
each twice). Then it reads, as the seeded admin, GET /dashboard and GET /dashboard/counters, and prints:
  - pendingSignups and olderThanWarn;
  - activeMembers;
  - classOccupancy and trainingBookings;
  - dogsByLevel: the number of columns, the level codes, `others` and `totalActiveDogs`.
It also prints, read-only from the stack's Mongo, the documents those values come from. No write is made
after the seeds. The access token and SEED_PASSWORD are never printed. The stack and its volumes are removed
at the end. Evidence only. Run from the repository root.
"""
import importlib.machinery
import importlib.util
import json
import os
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
loader = importlib.machinery.SourceFileLoader("e3smoke", str(ROOT / "bin/e3-smoke"))
spec = importlib.util.spec_from_loader("e3smoke", loader)
smoke_module = importlib.util.module_from_spec(spec)
loader.exec_module(smoke_module)


def show(label, value):
    print(f"VALUE {label} = {json.dumps(value, ensure_ascii=False)}", flush=True)


def probe(smoke):
    admin = smoke.call("POST", "/oauth2/token", quiet=True, form=dict(grant_type="password", client_id="clubs-admin",
                       username="admin@example.test", password=smoke.env["SEED_PASSWORD"]))["access_token"]
    dashboard = smoke.call("GET", "/api/v1/dashboard", access=admin)
    counters = smoke.call("GET", "/api/v1/dashboard/counters", access=admin)
    kpis = dashboard["kpis"]
    show("today", dashboard["today"])
    show("week", dashboard["week"])
    show("kpis.pendingSignups", kpis["pendingSignups"])
    show("pendingSignups.count", dashboard["pendingSignups"]["count"])
    show("pendingSignups.items[].pendingDays", [row["pendingDays"] for row in dashboard["pendingSignups"]["items"]])
    show("counters", counters)
    show("kpis.activeMembers", kpis["activeMembers"])
    show("kpis.classOccupancy", kpis["classOccupancy"])
    show("kpis.trainingBookings", kpis["trainingBookings"])
    levels = dashboard["dogsByLevel"]["levels"]
    show("dogsByLevel.columns", len(levels))
    show("dogsByLevel.levels[].code", [row["code"] for row in levels])
    show("dogsByLevel.levels[].total", [row["total"] for row in levels])
    show("dogsByLevel.others", dashboard["dogsByLevel"]["others"])
    show("dogsByLevel.totalActiveDogs", dashboard["dogsByLevel"]["totalActiveDogs"])
    show("dogsByLevel.activeDogWeeks", dashboard["dogsByLevel"]["activeDogWeeks"])
    show("riskReview.count", None if dashboard["riskReview"] is None else dashboard["riskReview"]["count"])
    club = json.dumps(smoke.club_id)
    week = dashboard["week"]
    show("mongo: active levels (code, progression) by order", smoke.mongo(
        "db.levels.find({clubId:" + club + ",active:true}).sort({order:1,_id:1}).toArray().map(l=>[l.code,l.progression!==false])"))
    show("mongo: members by status", smoke.mongo(
        "db.members.aggregate([{$match:{clubId:" + club + "}},{$group:{_id:'$status',n:{$sum:1}}},{$sort:{_id:1}}]).toArray()"))
    show("mongo: ACTIVE dogs", smoke.mongo("db.dogs.countDocuments({clubId:" + club + ",status:'ACTIVE'})"))
    show("mongo: class sessions of the dashboard week (ACTIVE/FINISHED)", smoke.mongo(
        "(()=>{const s=db.class_sessions.find({clubId:" + club + ",state:{$in:['ACTIVE','FINISHED']},date:{$gte:"
        + json.dumps(week["start"]) + ",$lte:" + json.dumps(week["end"]) + "}}).toArray();"
        "return {classes:s.length,capacity:s.reduce((n,c)=>n+Number(c.capacity),0),"
        "booked:s.reduce((n,c)=>n+Number(c.counters.booked),0),waiting:s.reduce((n,c)=>n+Number(c.counters.waiting||0),0)};})()"))
    show("mongo: training bookings", smoke.mongo("db.training_bookings.countDocuments({clubId:" + club + "})"))


def main():
    os.umask(0o077)
    code = 0
    with tempfile.TemporaryDirectory(prefix="agilityhub-e3t11-") as directory:
        smoke = smoke_module.Smoke(Path(directory), None)
        try:
            smoke.start()
            probe(smoke)
        except AssertionError as error:
            print("FAIL: " + str(error), flush=True)
            code = 1
        except Exception as error:  # controlled: the class name only, like the smoke
            print("FAIL: " + type(error).__name__, flush=True)
            code = 1
        finally:
            smoke.cleanup()
    print(f"dashboard_values exit code {code}", flush=True)
    sys.exit(code)


if __name__ == "__main__":
    main()
