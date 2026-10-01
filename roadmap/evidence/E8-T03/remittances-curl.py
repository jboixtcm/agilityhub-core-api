#!/usr/bin/env python3
"""E8-T03 evidence: SEPA remittances with curl on a disposable local Compose stack (the working tree's image).

Seeds the Cànic with its demo (club:apply + seed:demo, twice, through bin/e5-smoke's stack helpers), then, on this disposable
database only (fixture setup, as the smokes activate their clubs): gives the Cànic's SEPA_XML a fictional creditor (the seed
has none: S17 R-17-05) and makes every ACTIVE member owe November 2026. Then:
POST /billing/simulations → POST /billing/runs with one SEPA member's mandate removed (422 SEPA_NOT_CONFIGURED naming that
member; nothing written) → the mandate back, a new simulation and the run (the November remittance, ReqdColltnDt = day 1 of
the billed month) → GET /remittances → GET /remittances/{id} → GET /remittances/{id}/file → the signed local download
(headers, xmllint against the schema in use, the first 40 lines with the IBANs masked) → POST /remittances/{id}/submission →
POST /billing/runs/{id}/rollback (409 RUN_NOT_ROLLBACKABLE {reasons: [REMITTANCE_SUBMITTED]}). Prints the Remittance document
with its ids truncated and its creditor IBAN masked. Ids and tokens are truncated; no full IBAN is printed.
"""
import datetime
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import tempfile
import zoneinfo

ROOT = Path(__file__).resolve().parents[3]
SEEDS = (("club:apply", "seeds/club-canic.yaml"), ("seed:demo", "--club=canic", "--seed=42", "--week-start"))
XSD = ROOT / "src/main/resources/sepa/pain.008.001.02.xsd"
IBAN = re.compile(r"\b([A-Z]{2}[0-9]{2})([0-9 ]{6,30})([0-9]{4})\b")


def load_smoke():
    loader = importlib.machinery.SourceFileLoader("e5smoke", str(ROOT / "bin" / "e5-smoke"))
    spec = importlib.util.spec_from_loader("e5smoke", loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


def short(value):
    return None if value is None else str(value)[:8] + "…"


def masked(text):
    """Every IBAN as its country, check digits and last four: «ES00 ···· 4321»."""
    return IBAN.sub(lambda match: match.group(1) + " ···· " + match.group(3), text)


def show(title, value):
    print(f"--- {title}", flush=True)
    print(masked(json.dumps(value, ensure_ascii=False, indent=1, default=str)), flush=True)


PREPARE = r"""
(() => {
  const club = CLUB;
  db.clubs.updateOne({_id: club}, {$set: {"paymentProviders.SEPA_XML": {enabled: true, creditorName: "Club d'Agility Cànic (fictional creditor)",
      creditorId: "ES00ZZZG00000000", iban: "ES0000000000000000009876", suffix: "000"}}});
  db.members.updateMany({clubId: club, status: "ACTIVE"}, {$set: {nextInvoiceDate: "2026-11-01"}});
  const sepa = db.members.find({clubId: club, status: "ACTIVE", "paymentMethod.type": "SEPA_DD"}).toArray();
  const complete = sepa.filter(m => m.paymentMethod.mandateRef && m.paymentMethod.mandateSignedAt && (m.paymentMethod.iban || m.paymentMethod.ibanEncrypted));
  return {active: db.members.countDocuments({clubId: club, status: "ACTIVE"}), sepa: sepa.length, complete: complete.length,
          watched: complete.length ? complete[0]._id : null};
})()
"""


def run(smoke, admin, label, expected=201, error=None):
    simulation = smoke.call("POST", "/api/v1/billing/simulations", 201, access=admin, body={"period": "2026-11"})
    kpis = simulation["kpis"]
    smoke.record(f"POST /billing/simulations ({label})", "201", f"count {kpis['count']} · byProvider "
                 f"{json.dumps({k: v['count'] for k, v in kpis['byProvider'].items()})} · incidents {sorted({i['code'] for i in simulation['incidents']})}")
    return smoke.call("POST", "/api/v1/billing/runs", expected, access=admin, idempotent=True, error=error, body={"period": "2026-11", "simulationId": simulation["id"]})


def scenario(smoke, private):
    club = smoke.canic
    admin = smoke.login("admin@example.test", "clubs-admin")
    prepared = smoke.mongo(PREPARE.replace("CLUB", json.dumps(club)))
    smoke.record("fixture (disposable database only)", "ok", f"fictional SEPA_XML creditor; {prepared['active']} ACTIVE members owe 2026-11; "
                 f"{prepared['sepa']} pay by SEPA_DD, {prepared['complete']} with a complete mandate")
    if not prepared["watched"] or prepared["complete"] != prepared["sepa"]:
        raise AssertionError("The demo seed's SEPA members must all carry their mandate (E5-T28)")
    watched = prepared["watched"]
    # Step 6: a SEPA collection without a mandate reaches the writer → 422 SEPA_NOT_CONFIGURED naming the member; nothing is written.
    mandate = smoke.mongo('db.members.findOne({_id:' + json.dumps(watched) + '}).paymentMethod.mandateRef')
    smoke.mongo('db.members.updateOne({_id:' + json.dumps(watched) + '},{$unset:{"paymentMethod.mandateRef":""}}).modifiedCount')
    refused = run(smoke, admin, "a SEPA member without its mandate", 422, "SEPA_NOT_CONFIGURED")
    written = smoke.mongo('[db.invoices.countDocuments({clubId:' + json.dumps(club) + '}),db.remittances.countDocuments({clubId:' + json.dumps(club) + '}),'
                          'db.billing_runs.countDocuments({clubId:' + json.dumps(club) + '})]')
    smoke.record("POST /billing/runs (one SEPA member without mandateRef)", "422 SEPA_NOT_CONFIGURED",
                 f"details.memberIds {[short(m) for m in refused['details']['memberIds']]} (the watched member {short(watched)}) · "
                 f"invoices/remittances/runs written {written}")
    if refused["details"]["memberIds"] != [watched] or written != [0, 0, 0]:
        raise AssertionError("The refused run must name the member and write nothing")
    smoke.mongo('db.members.updateOne({_id:' + json.dumps(watched) + '},{$set:{"paymentMethod.mandateRef":' + json.dumps(mandate) + '}}).modifiedCount')

    result = run(smoke, admin, "the mandate back")
    remittance, billing_run = result["remittance"], result["run"]
    smoke.record("POST /billing/runs", "201", f"run {short(billing_run['id'])} {billing_run['status']} · {len(billing_run['invoiceIds'])} invoices · "
                 f"collectionDate {billing_run['collectionDate']} · remittance {remittance['messageId']} ({remittance['count']} debits, "
                 f"{remittance['total']['amountMinor']} {remittance['total']['currency']}) · sequenceBreakdown {remittance['sequenceBreakdown']}")
    page = smoke.call("GET", "/api/v1/remittances", access=admin)
    show("GET /remittances (items)", [dict(row, id=short(row["id"])) for row in page["items"]])
    smoke.record("GET /remittances", "200", f"totalItems {page['totalItems']}")
    detail = smoke.call("GET", f"/api/v1/remittances/{remittance['id']}", access=admin)
    show("GET /remittances/{id}", dict(detail, id=short(detail["id"]), runId=short(detail["runId"]), collectionIds=f"{len(detail['collectionIds'])} ids",
                                       submittedByAccountId=short(detail.get("submittedByAccountId"))))
    link = smoke.call("GET", f"/api/v1/remittances/{remittance['id']}/file", access=admin)
    smoke.record("GET /remittances/{id}/file", "200", f"fileName {link['fileName']} · expiresAt {link['expiresAt']} · downloadUrl "
                 f"{re.sub('signature=[0-9a-f]+', 'signature=<hmac>', re.sub('[0-9a-f]{8}-[0-9a-f-]{27}', '<id>', link['downloadUrl']))}")
    # The signed local link authorises itself: no bearer and no host header.
    headers, xml_path = private / "headers", private / "remesa.xml"
    status = subprocess.run(["curl", "-4", "--silent", "--show-error", "--noproxy", "*", "--max-time", "60", "--dump-header", str(headers),
                             "--output", str(xml_path), "--write-out", "%{http_code}", smoke.base + link["downloadUrl"]],
                            cwd=ROOT, env=smoke.env, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout
    kept = [line.strip() for line in headers.read_text().splitlines() if line.lower().split(":")[0] in ("content-type", "content-disposition", "cache-control")]
    smoke.record("GET <signed downloadUrl> (no bearer)", status, " · ".join(kept))
    if status != "200":
        raise AssertionError("The signed link did not download the file")
    lint = subprocess.run(["xmllint", "--noout", "--schema", str(XSD), str(xml_path)], cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    print("$ xmllint --noout --schema src/main/resources/sepa/pain.008.001.02.xsd remesa-2026-11.xml", flush=True)
    print(lint.stdout.replace(str(xml_path), "remesa-2026-11.xml").strip() + f"\nexit {lint.returncode}", flush=True)
    if lint.returncode:
        raise AssertionError("xmllint refused the file")
    text = xml_path.read_text()
    print("--- the first 40 lines of the downloaded file (IBANs masked here; the file carries them in full)", flush=True)
    print(masked("\n".join(text.splitlines()[:40])), flush=True)
    seqs = sorted(set(re.findall(r"<SeqTp>([A-Z]+)</SeqTp>", text)))
    smoke.record("the file", "ok", f"{len(text.encode())} bytes · NbOfTxs {re.search('<NbOfTxs>([0-9]+)</NbOfTxs>', text).group(1)} · CtrlSum "
                 f"{re.search('<CtrlSum>([0-9.]+)</CtrlSum>', text).group(1)} · SeqTp {seqs} · ReqdColltnDt {re.search('<ReqdColltnDt>([0-9-]+)<', text).group(1)} · "
                 f"full debtor IBANs in the file {len(IBAN.findall(text)) - 1}")
    stored = smoke.mongo('db.remittances.findOne({_id:' + json.dumps(remittance["id"]) + '})')
    show("the Remittance document", {k: (short(v) if k in ("_id", "runId", "createdByAccountId") else
                                         f"{len(v)} ids" if k == "collectionIds" else
                                         re.sub("[0-9a-f]{8}-[0-9a-f-]{27}", "<id>", v) if k == "fileKey" else v)
                                     for k, v in stored.items()})

    today = datetime.datetime.now(zoneinfo.ZoneInfo("Europe/Madrid")).date().isoformat()
    submitted = smoke.call("POST", f"/api/v1/remittances/{remittance['id']}/submission", 200, access=admin, idempotent=True, body={"submittedAt": today})
    smoke.record("POST /remittances/{id}/submission", "200", f"status {submitted['status']} · submittedAt {submitted['submittedAt']} · "
                 f"submittedByAccountId {short(submitted['submittedByAccountId'])}")
    blocked = smoke.call("POST", f"/api/v1/billing/runs/{billing_run['id']}/rollback", 409, access=admin, idempotent=True, error="RUN_NOT_ROLLBACKABLE",
                         body={"reason": "Massa tard", "confirmation": "RETROCEDIR"})
    smoke.record("POST /billing/runs/{id}/rollback", "409 RUN_NOT_ROLLBACKABLE", json.dumps(blocked["details"]))
    if blocked["details"] != {"reasons": ["REMITTANCE_SUBMITTED"]}:
        raise AssertionError("The rollback must be refused for REMITTANCE_SUBMITTED only")
    audit = smoke.mongo('db.audit_entries.findOne({clubId:' + json.dumps(club) + ',action:"REMITTANCE_SUBMITTED"})')
    show("AuditEntry REMITTANCE_SUBMITTED", {"action": audit["action"], "entityType": audit["entityType"], "entityId": short(audit["entityId"]),
                                             "actorRole": audit["actorRole"], "changes": audit["changes"], "details": audit.get("details")})


def main():
    module = load_smoke()
    os.umask(0o077)
    signal.signal(signal.SIGTERM, lambda number, frame: sys.exit(128 + number))
    with tempfile.TemporaryDirectory(prefix="agilityhub-e8t03-") as directory:
        smoke = module.Smoke(Path(directory), None)
        try:
            smoke.start(seeds=SEEDS, label="Cànic")
            smoke.host = smoke.hosts["canic"]
            scenario(smoke, Path(directory))
            print("\nSUMMARY (step · status · key values)", flush=True)
            for step, status, values in smoke.summary:
                print(f"  {step} · {status}" + (f" · {values}" if values else ""), flush=True)
            print("PASS E8-T03 curl sequence", flush=True)
        finally:
            smoke.cleanup()


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, OSError, subprocess.SubprocessError, StopIteration, KeyError, TypeError, IndexError) as error:
        print("FAIL: " + (str(error) if isinstance(error, AssertionError) else type(error).__name__ + " " + str(error)[:200]), file=sys.stderr)
        sys.exit(1)
