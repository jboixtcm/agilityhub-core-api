#!/usr/bin/env python3
"""E8-T02 evidence: the S12 monthly cycle with curl on a disposable local Compose stack (the working tree's image).

Seeds the Cànic with its demo (club:apply + seed:demo, twice, through bin/e5-smoke's stack helpers), then prepares the
fictional census for billing on this disposable database only (fixture setup, as the smokes activate their clubs): every
ACTIVE member owes September (`nextInvoiceDate = 2026-09-01`), and six members carry one incident each of R-12-07. Then:
POST /billing/simulations → GET /billing/periods/2026-09 → POST /billing/runs (the default collection day is too soon on
today's date: 422, then an explicit date) → GET /invoices?filter=period:eq:2026-09 → POST /invoices/payments (cash
adjustment receipts issued before the run) → POST /billing/runs/{id}/rollback → the same simulation and run again with the
identical displayNumber of every member → a cash receipt paid (the run stops being rollbackable) → a bank return of a
remitted receipt with N-10 → its PDF. Prints one member's Invoice, Collection and BillingRun before and after the
rollback, the generation's AuditEntry and the simulation's incidents. Ids and tokens are truncated; no IBAN is printed.
"""
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[3]
SEEDS = (("club:apply", "seeds/club-canic.yaml"), ("seed:demo", "--club=canic", "--seed=42", "--week-start"))


def load_smoke():
    loader = importlib.machinery.SourceFileLoader("e5smoke", str(ROOT / "bin" / "e5-smoke"))
    spec = importlib.util.spec_from_loader("e5smoke", loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


def short(value):
    return None if value is None else str(value)[:8] + "…"


def plain(value):
    """mongosh's JSON.stringify prints a NumberLong as {high, low, unsigned}: show it as the number it is."""
    if isinstance(value, dict):
        if set(value) == {"high", "low", "unsigned"}:
            return value["high"] * 2 ** 32 + (value["low"] & 0xFFFFFFFF)
        return {k: plain(v) for k, v in value.items()}
    if isinstance(value, list):
        return [plain(v) for v in value]
    return value


def show(title, value):
    print(f"--- {title}", flush=True)
    print(json.dumps(plain(value), ensure_ascii=False, indent=1, default=str), flush=True)


PREPARE = r"""
(() => {
  const club = CLUB;
  const sepa = db.members.find({clubId: club, status: "ACTIVE", "paymentMethod.type": "SEPA_DD", familyGroupId: null}).sort({memberNumber: 1}).limit(6).toArray();
  const abonat = db.plans.findOne({clubId: club, code: "ABONAT"});
  const price = db.prices.findOne({clubId: club, planId: abonat._id, concept: "MONTHLY_FEE"});
  const unpriced = Object.assign({}, abonat, {_id: "e8t02-plan-unpriced", code: "E8T02_SENSE_PREU"});
  const dollars = Object.assign({}, abonat, {_id: "e8t02-plan-dollars", code: "E8T02_DOLARS"});
  db.plans.insertMany([unpriced, dollars]);
  db.prices.insertOne(Object.assign({}, price, {_id: "e8t02-price-dollars", planId: "e8t02-plan-dollars", amount: {amountMinor: price.amount.amountMinor, currency: "USD"}}));
  db.members.updateMany({clubId: club, status: "ACTIVE"}, {$set: {nextInvoiceDate: "2026-09-01"}});
  db.members.updateOne({_id: sepa[0]._id}, {$unset: {"paymentMethod.iban": "", "paymentMethod.ibanEncrypted": ""}});
  db.members.updateOne({_id: sepa[1]._id}, {$unset: {planId: ""}});
  db.members.updateOne({_id: sepa[2]._id}, {$set: {planId: "e8t02-plan-unpriced"}});
  db.members.updateOne({_id: sepa[3]._id}, {$set: {planId: "e8t02-plan-dollars"}});
  db.members.updateOne({_id: sepa[4]._id}, {$set: {paymentMethod: {type: "CARD", card: {stripeCustomerId: "cus_e8t02", last4: "4242", invalid: true}}}});
  db.members.updateOne({_id: sepa[5]._id}, {$set: {paymentMethod: {type: "CARD", card: {stripeCustomerId: "cus_e8t02b", last4: "1881", invalid: false}}}});
  const cash = db.members.find({clubId: club, status: "ACTIVE", "paymentMethod.type": "MANUAL", familyGroupId: null}).sort({memberNumber: 1}).limit(4).toArray();
  return {incidentMembers: sepa.map(m => m._id), cash: cash.map(m => m._id), active: db.members.countDocuments({clubId: club, status: "ACTIVE"})};
})()
"""


def documents(smoke, member):
    invoice = smoke.mongo('db.invoices.find({memberId:' + json.dumps(member) + ',kind:"PERIODIC"}).sort({createdAt:-1}).limit(1).toArray()[0]')
    attempts = smoke.mongo('db.collections.find({invoiceId:' + json.dumps(invoice["_id"]) + '}).sort({createdAt:1}).toArray()')
    run = smoke.mongo('db.billing_runs.findOne({_id:' + json.dumps(invoice["runId"]) + '})')
    return {
        "Invoice": {"_id": short(invoice["_id"]), "displayNumber": invoice["displayNumber"], "status": invoice["status"], "issueDate": invoice["issueDate"],
                    "period": invoice["period"], "total": invoice["total"], "runId": short(invoice["runId"]), "remittanceId": short(invoice.get("remittanceId")),
                    "cancelReason": invoice.get("cancelReason"), "paymentMethod": {k: v for k, v in invoice["paymentMethod"].items() if k in ("type", "maskedAccount")},
                    "lines": [{"origin": l["origin"], "description": l["description"], "total": l["total"]} for l in invoice["lines"]], "version": invoice.get("version")},
        "Collections": [{"provider": c["provider"], "status": c["status"], "attempt": c["attempt"], "failureCode": c.get("failureCode"),
                         "remittanceId": short(c.get("remittanceId")), "endToEndId": c.get("endToEndId")} for c in attempts],
        "BillingRun": {"_id": short(run["_id"]), "status": run["status"], "period": run["period"], "firstNumber": run["firstNumber"],
                       "counterKey": run.get("counterKey"), "invoiceIds": len(run["invoiceIds"]), "collectionDate": run.get("collectionDate"),
                       "rolledBackAt": run.get("rolledBackAt"), "rollbackReason": run.get("rollbackReason"),
                       "previousDates[member]": [dict(p, memberId=short(p["memberId"])) for p in run["previousDates"] if p["memberId"] == member]},
        "Member.nextInvoiceDate": smoke.mongo('db.members.findOne({_id:' + json.dumps(member) + '}).nextInvoiceDate'),
    }


def scenario(smoke):
    club = smoke.canic
    admin = smoke.login("admin@example.test", "clubs-admin")
    prepared = smoke.mongo(PREPARE.replace("CLUB", json.dumps(club)))
    smoke.record("fixture (disposable database only)", "ok", f"{prepared['active']} ACTIVE members owe 2026-09; six members with one incident each")
    # Cash adjustment receipts issued before the run: paid after it, they never block its rollback (R-12-14).
    manual = []
    for member in prepared["cash"]:
        receipt = smoke.call("POST", "/api/v1/invoices", 201, access=admin, idempotent=True, body={"memberId": member, "note": "E8-T02 evidence",
                             "lines": [{"description": "Material del club", "base": {"amountMinor": 1500, "currency": "EUR"}, "taxPercent": 0}]})
        manual.append(receipt["id"])
    smoke.record("POST /invoices ×4 (cash adjustments, before the run)", "201", ", ".join(smoke.mongo(
        'db.invoices.find({_id:{$in:' + json.dumps(manual) + '}}).sort({number:1}).toArray().map(i=>i.displayNumber)')))

    simulation = smoke.call("POST", "/api/v1/billing/simulations", 201, access=admin, body={"period": "2026-09"})
    incidents = [{"memberId": short(i["memberId"]), "memberName": i["memberName"], "code": i["code"]} for i in simulation["incidents"]]
    show("simulation incidents (R-12-07)", incidents)
    kpis = simulation["kpis"]
    smoke.record("POST /billing/simulations", "201", f"{len(incidents)} incidents · codes {sorted({i['code'] for i in incidents})} · count {kpis['count']} · "
                 f"total {kpis['total']['amountMinor']} · byProvider {json.dumps({k: v['count'] for k, v in kpis['byProvider'].items()})} · cashPending {kpis['cashPending']} · "
                 f"cashMembers {len(simulation['cashMembers'])}")
    period = smoke.call("GET", "/api/v1/billing/periods/2026-09", access=admin)
    smoke.record("GET /billing/periods/2026-09", "200", f"simulation {short(period['simulation']['id'])} · run {period['run']} · counts {period['counts']}")

    too_soon = smoke.call("POST", "/api/v1/billing/runs", 422, access=admin, idempotent=True, error="COLLECTION_DATE_TOO_SOON",
                          body={"period": "2026-09", "simulationId": simulation["id"]})
    earliest = too_soon["details"]["earliest"]
    smoke.record("POST /billing/runs (default day 1 of 2026-09, today)", "422 COLLECTION_DATE_TOO_SOON", json.dumps(too_soon["details"]))
    body = {"period": "2026-09", "simulationId": simulation["id"], "collectionDate": earliest}
    first = smoke.call("POST", "/api/v1/billing/runs", 201, access=admin, idempotent=True, body=body)
    run = first["run"]
    smoke.record("POST /billing/runs (collectionDate " + earliest + ")", "201",
                 f"run {short(run['id'])} {run['status']} · {len(run['invoiceIds'])} invoices · skipped {len(first['skipped'])} · "
                 f"remittance {first['remittance']['messageId'] if first['remittance'] else None} ({first['remittance']['count'] if first['remittance'] else 0} SEPA)")
    numbers = {i["memberId"]: i["displayNumber"] for i in smoke.mongo('db.invoices.find({runId:' + json.dumps(run["id"]) + '}).toArray()')}
    listed = smoke.call("GET", "/api/v1/invoices?filter=period:eq:2026-09&sort=number,asc", access=admin)
    show("GET /invoices?filter=period:eq:2026-09 (first 5 rows)", [{k: r.get(k) for k in ("displayNumber", "concept", "total", "paymentMethodType", "status")}
                                                                  for r in listed["items"][:5]])
    smoke.record("GET /invoices?filter=period:eq:2026-09", "200", f"totalItems {listed['totalItems']}")
    # The watched member: the first remitted (SEPA) receipt of the run.
    watched = smoke.mongo('db.invoices.find({runId:' + json.dumps(run["id"]) + ',status:"COLLECTING"}).sort({number:1}).limit(1).toArray()[0].memberId')
    before = documents(smoke, watched)
    show("before the rollback", before)
    audit = smoke.mongo('db.audit_entries.findOne({clubId:' + json.dumps(club) + ',action:"REMITTANCE_GENERATED"})')
    show("AuditEntry of the generation", {"action": audit["action"], "entityType": audit["entityType"], "entityId": short(audit["entityId"]),
                                          "actorRole": audit["actorRole"], "origin": audit.get("origin"),
                                          "details.invoiceIds": [short(i) for i in audit["details"]["invoiceIds"][:3]] + [f"… ({len(audit['details']['invoiceIds'])} ids)"],
                                          "details.period": audit["details"]["period"]})
    print("audit entries REMITTANCE_GENERATED of the club: " + str(smoke.mongo('db.audit_entries.countDocuments({clubId:' + json.dumps(club) + ',action:"REMITTANCE_GENERATED"})')), flush=True)

    paid = smoke.call("POST", "/api/v1/invoices/payments", 200, access=admin, idempotent=True,
                      body={"invoiceIds": manual, "paidAt": smoke.mongo('db.invoices.findOne({_id:' + json.dumps(manual[0]) + '}).issueDate'), "channel": "CASH"})
    smoke.record("POST /invoices/payments (the 4 cash adjustments)", "200", f"paid {paid['paid']} · statuses {sorted({i['status'] for i in paid['invoices']})}")
    smoke.call("POST", f"/api/v1/billing/runs/{run['id']}/rollback", 400, access=admin, idempotent=True, error="VALIDATION_ERROR",
               body={"reason": "Preu equivocat", "confirmation": "retrocedir"})
    rollback = smoke.call("POST", f"/api/v1/billing/runs/{run['id']}/rollback", 200, access=admin, idempotent=True,
                          body={"reason": "Preu equivocat", "confirmation": "RETROCEDIR"})
    smoke.record("POST /billing/runs/{id}/rollback", "200", json.dumps(rollback))
    after = documents(smoke, watched)
    show("after the rollback", after)

    again = smoke.call("POST", "/api/v1/billing/simulations", 201, access=admin, body={"period": "2026-09"})
    second = smoke.call("POST", "/api/v1/billing/runs", 201, access=admin, idempotent=True,
                        body={"period": "2026-09", "simulationId": again["id"], "collectionDate": earliest})
    renumbered = {i["memberId"]: i["displayNumber"] for i in smoke.mongo('db.invoices.find({runId:' + json.dumps(second["run"]["id"]) + '}).toArray()')}
    same = renumbered == numbers
    smoke.record("POST /billing/runs again (new simulation)", "201", f"run {short(second['run']['id'])} · identical displayNumber per member: {same} "
                 f"({min(numbers.values())}…{max(numbers.values())}, {len(numbers)} invoices)")
    if not same:
        raise AssertionError("The second generation did not reproduce the numbers")
    # A cash receipt of the run paid: the run is no longer rollbackable (R-12-14).
    cash_id = smoke.mongo('db.invoices.findOne({runId:' + json.dumps(second["run"]["id"]) + ',"paymentMethod.type":"MANUAL"})._id')
    cash_invoice = smoke.call("GET", f"/api/v1/invoices/{cash_id}", access=admin)
    smoke.call("POST", f"/api/v1/invoices/{cash_id}/payment", 200, access=admin, idempotent=True,
               body={"paidAt": cash_invoice["issueDate"], "channel": "CASH", "version": cash_invoice["version"]})
    blocked = smoke.call("GET", "/api/v1/billing/periods/2026-09", access=admin)
    smoke.record("GET /billing/periods/2026-09 after a cash payment", "200", f"rollbackable {blocked['run']['rollbackable']} · blockers {blocked['run']['rollbackBlockers']}")
    sepa_id = smoke.mongo('db.invoices.findOne({runId:' + json.dumps(second["run"]["id"]) + ',status:"COLLECTING"})._id')
    sepa_invoice = smoke.call("GET", f"/api/v1/invoices/{sepa_id}", access=admin)
    failed = smoke.call("POST", f"/api/v1/invoices/{sepa_id}/failure", 200, access=admin, idempotent=True,
                        body={"reason": "Devolució del banc", "at": cash_invoice["issueDate"], "version": sepa_invoice["version"]})
    smoke.record("POST /invoices/{id}/failure (a remitted SEPA receipt)", "200", f"{failed['status']} · collections {[c['status'] for c in failed['collections']]}")
    # N-10 reaches the admins once the scheduler's outbox dispatch has run (bodies not printed: they name the member).
    query = '{clubId:' + json.dumps(club) + ',code:"N-10",body:{$regex:' + json.dumps(failed["displayNumber"]) + '}}'
    notices = 0
    for _ in range(30):
        notices = smoke.mongo('db.notifications.countDocuments(' + query + ')')
        if notices:
            break
        time.sleep(2)
    smoke.record("N-10 after the bank return", "ok" if notices else "missing", f"{notices} notification(s) naming {failed['displayNumber']} · "
                 f"N-35 {smoke.mongo('db.notifications.countDocuments({clubId:' + json.dumps(club) + ',code:\"N-35\"})')}")
    if not notices:
        raise AssertionError("N-10 was not delivered")
    pdf = smoke.download(f"/api/v1/invoices/{sepa_id}/document", admin)
    smoke.record("GET /invoices/{id}/document", "200", f"{len(pdf)} bytes · starts {pdf[:5]!r}")


def main():
    module = load_smoke()
    os.umask(0o077)
    signal.signal(signal.SIGTERM, lambda number, frame: sys.exit(128 + number))
    with tempfile.TemporaryDirectory(prefix="agilityhub-e8t02-") as directory:
        smoke = module.Smoke(Path(directory), None)
        try:
            smoke.start(seeds=SEEDS, label="Cànic")
            smoke.host = smoke.hosts["canic"]
            scenario(smoke)
            print("\nSUMMARY (step · status · key values)", flush=True)
            for step, status, values in smoke.summary:
                print(f"  {step} · {status}" + (f" · {values}" if values else ""), flush=True)
            print("PASS E8-T02 curl sequence", flush=True)
        finally:
            smoke.cleanup()


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, OSError, subprocess.SubprocessError, StopIteration, KeyError, TypeError, IndexError) as error:
        print("FAIL: " + (str(error) if isinstance(error, AssertionError) else type(error).__name__ + " " + str(error)[:200]), file=sys.stderr)
        sys.exit(1)
