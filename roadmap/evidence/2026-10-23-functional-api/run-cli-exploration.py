#!/usr/bin/env python3
"""Black-box CLI exploration of the exact final image, with an isolated disposable database."""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[3]
EVIDENCE = Path(__file__).resolve().parent
IMAGE = "agilityhub-core-api:review-20261023"
EXPECTED_IMAGE = "sha256:1e8060615d372dc826ea431e1cd9ef3733c440d7ce4ecc02199f708e9c6d752e"
BUSINESS = ["members", "dogs", "family_groups", "accounts", "memberships", "migration_runs", "invoices",
            "pack_balances", "notifications", "magic_link_tokens", "audit_entries", "domain_events"]


def shell(command, *, check=True, timeout=120):
    result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f"{command[0]} {command[1]} failed: {result.stderr[-300:]}")
    return result


def main():
    attempt = int(sys.argv[1]) if len(sys.argv)>1 else 1
    out = EVIDENCE / f"attempt-{attempt:02d}"
    out.mkdir(exist_ok=False)
    os.umask(0o077)
    image = json.loads(shell(["docker", "image", "inspect", IMAGE]).stdout)[0]
    assert image["Id"] == EXPECTED_IMAGE
    (out / "image.json").write_text(json.dumps({"tag": IMAGE, "id": image["Id"], "architecture": image["Architecture"],
        "profile": "local", "network": "internal, no published ports, no provider egress"}, indent=2)+"\n")
    project = "functional-cli-"+secrets.token_hex(4)
    network, mongo = project+"-net", project+"-mongo"
    cases = []
    active = []
    serial = 0
    with tempfile.TemporaryDirectory(prefix="agilityhub-fictional-cli-") as directory:
        work = Path(directory)
        fixture = work / "input"
        shutil.copytree(ROOT / "src/test/resources/fixtures/playoff", fixture)
        fixture.chmod(0o755)
        for path in fixture.iterdir():
            if path.is_file(): path.chmod(0o644)
        env = {"SPRING_PROFILES_ACTIVE": "local", "MONGODB_HOST": mongo, "MONGODB_DATABASE": "functional",
               "MONGODB_REPLICA_SET": "rs0", "MAIL_LOCAL_DIRECTORY": "/app/mailbox",
               "AUTH_ISSUER": "https://id.example.test", "OIDC_LOGIN_URL": "https://id.example.test/login", "ATTACHMENT_LOCAL_DIRECTORY": "/app/attachments",
               "EXPORT_LOCAL_DIRECTORY": "/app/exports"}
        for key in ("OIDC_MASTER_KEY", "BILLING_BANK_KEY", "BILLING_SECRETS_KEY", "SIGNUP_CAPABILITY_KEY",
                    "BOOKING_CALENDAR_KEY", "EMAIL_UNSUBSCRIBE_KEY"):
            env[key] = base64.b64encode(secrets.token_bytes(32)).decode()
        env["SEED_PASSWORD"] = "Aa9!" + secrets.token_urlsafe(28)
        envfile = work / "runtime.env"
        envfile.write_text("".join(key+"="+value+"\n" for key,value in env.items()))
        envfile.chmod(0o600)

        def js(expression):
            result = shell(["docker", "exec", mongo, "mongosh", "--quiet", "functional", "--eval", "JSON.stringify("+expression+")"])
            return json.loads(result.stdout.strip())

        def snapshot():
            documents = js("Object.fromEntries("+json.dumps(BUSINESS)+".map(name=>[name,db.getCollection(name).find({}).sort({_id:1}).toArray()]))")
            return {name: {"count": len(values), "sha256": hashlib.sha256(json.dumps(values,sort_keys=True).encode()).hexdigest()}
                    for name, values in documents.items()}

        def check(label, condition, detail):
            cases.append({"case":label,"passed":bool(condition),"detail":detail})
            print(("PASS " if condition else "FAIL ")+label, flush=True)
            (out / "results.json").write_text(json.dumps(cases,indent=2)+"\n")

        def cli(label, command, arguments, expected=0, mailbox=None):
            nonlocal serial
            serial += 1
            name=project+"-cmd-"+str(serial)
            active.append(name)
            invocation=["docker","run","--rm","--name",name,"--network",network,"--env-file",str(envfile),
                        "--mount",f"type=bind,src={fixture},dst=/private-input,readonly"]
            if mailbox is not None: invocation += ["--env","MAIL_LOCAL_DIRECTORY="+mailbox]
            invocation += [IMAGE,"--core.command="+command,*arguments]
            before=snapshot()
            result=shell(invocation,check=False,timeout=240)
            after=snapshot()
            stem=out/f"{serial:02d}-{label}"
            stem.with_suffix(".command").write_text(shlex.join(invocation)+"\n")
            stem.with_suffix(".exit").write_text(str(result.returncode)+"\n")
            log=result.stdout+result.stderr
            for value in env.values():
                if len(value)>25: log=log.replace(value,"[ephemeral configuration redacted]")
            log=re.sub(r"(?<![a-zA-Z0-9])[a-f0-9]{64}(?![a-zA-Z0-9])","[manifest SHA-256 redacted]",log)
            stem.with_suffix(".log").write_text(log)
            summary = {label: {name: {"count": state["count"], "sha256Prefix": state["sha256"][:16]}
                              for name, state in states.items()} for label, states in {"before": before, "after": after}.items()}
            stem.with_name(stem.name+"-state.json").write_text(json.dumps(summary,indent=2)+"\n")
            check(label+" exit",result.returncode==expected,{"expected":expected,"actual":result.returncode})
            active.remove(name)
            if label.startswith("seed-") and result.returncode:
                raise RuntimeError("Fixture seeding failed; see " + stem.name + ".log")
            return result,before,after

        common=["--in=/private-input","--club=canic","--cut-over=2026-09-01"]
        try:
            shell(["docker","network","create","--internal",network])
            shell(["docker","run","-d","--name",mongo,"--network",network,"mongo:7","mongod","--replSet","rs0","--bind_ip_all"])
            for _ in range(60):
                if shell(["docker","exec",mongo,"mongosh","--quiet","--eval","db.adminCommand({ping:1})"],check=False).returncode==0: break
                time.sleep(1)
            shell(["docker","exec",mongo,"mongosh","--quiet","--eval",f'rs.initiate({{_id:"rs0",members:[{{_id:0,host:"{mongo}:27017"}}]}})'])
            for _ in range(60):
                if js("db.hello().isWritablePrimary"): break
                time.sleep(1)
            cli("seed-canic","club:apply",["seeds/club-canic.yaml"])
            cli("seed-other","club:apply",["seeds/club-minim.yaml"])
            club=js('db.clubs.findOne({slug:"canic"})._id')
            other=js('db.clubs.findOne({slug:"minim"})._id')
            initial_accounts=js("db.accounts.countDocuments({})")
            result,before,after=cli("dry-partial","migration:playoff",common+["--dry-run","--steps=1-3"])
            check("partial dry-run zero business writes",before==after,{})
            cli("apply-partial","migration:playoff",common+["--apply","--steps=1-3"])
            run=js(f'db.migration_runs.findOne({{clubId:{json.dumps(club)}}},{{}})')
            check("partial person-only state",js('db.members.countDocuments({status:"PENDING"})')==187 and js('db.dogs.countDocuments({})')==0
                  and js('db.accounts.countDocuments({})')==initial_accounts and js('db.members.countDocuments({paymentMethod:{$exists:true}})')==0,{"completed":run.get("execution",{}).get("completedThisRun")})
            result,before,after=cli("resume-drift","migration:playoff",[a for a in common if not a.startswith("--cut-over")]+["--cut-over=2026-09-02","--apply","--steps=4-10","--resume-run="+run["_id"]],1)
            check("cutover drift refuses without writes",before==after and "INPUT_SCHEMA_MISMATCH" in result.stderr,{})
            cli("resume-complete","migration:playoff",common+["--apply","--steps=4-10","--resume-run="+run["_id"]])
            check("complete census cardinalities",js('db.members.countDocuments({})')==187 and js('db.dogs.countDocuments({})')==189,{})
            result,before,after=cli("stale-resume","migration:playoff",common+["--apply","--steps=4-10","--resume-run="+run["_id"]],1)
            check("stale resume refuses without writes",before==after and "MIGRATION_ALREADY_APPLIED" in result.stderr,{})
            result,before,after=cli("legacy-full-repeat","migration:playoff",common+["--apply"])
            check("full import repeat creates no duplicate entities",all(before[k]["count"]==after[k]["count"] for k in ["members","dogs","accounts","memberships","family_groups"]),{})
            result,before,after=cli("production-no-confirmation","migration:playoff",common+["--apply","--env=production"],1)
            check("production confirmation enforced before writes",before==after and "PRODUCTION_REQUIRES_CONFIRMATION" in result.stderr,{})
            result,before,after=cli("production-reset","migration:playoff",["--club=canic","--env=production","--reset"],1)
            check("explicit production reset forbidden",before==after and "FORBIDDEN" in result.stderr,{})
            result,before,after=cli("reapply-without-file","migration:playoff",common+["--apply","--env=production","--confirm-production","--allow-reapply"],1)
            check("reapply requires written approval",before==after and "Usage:" in result.stderr,{})
            result,before,after=cli("duplicate-steps","migration:playoff",common+["--dry-run","--steps=1-3","--steps=4-10"],1)
            check("duplicate option rejected",before==after and "Usage:" in result.stderr,{})
            result,before,after=cli("invalid-step","migration:playoff",common+["--dry-run","--steps=0,11"],1)
            check("out-of-range steps rejected",before==after,{})
            result,before,after=cli("production-local-storage","migration:playoff",common+["--apply","--env=production","--confirm-production","--steps=1-10"],1)
            check("unconfigured production report store fails before import",before==after and "IllegalStateException" in result.stderr,{})
            result,before,after=cli("report-wrong-tenant","migration:report",["--club=minim","--run="+run["_id"],"--in=/private-input"],1)
            check("report lookup tenant isolation",before==after and "NOT_FOUND" in result.stderr,{})
            result,before,after=cli("report-staging","migration:report",["--club=canic","--run="+run["_id"],"--in=/private-input"],1)
            check("staging report not publishable",before==after and "INVALID_STATE" in result.stderr,{})
            numbers=js('db.members.find({status:"ACTIVE",accountId:{$ne:null},memberNumber:{$ne:null}},{memberNumber:1}).sort({memberNumber:1}).limit(2).toArray().map(x=>x.memberNumber)')
            assert len(numbers)==2
            welcome=["--club=canic","--only-numbers="+str(numbers[0]),"--interval=1s"]
            result,before,after=cli("welcome-dry","migration:welcome",welcome+["--dry-run"])
            check("welcome dry-run leaves no claim/token/notification",before==after and "ELIGIBLE=1" in result.stdout,{})
            result,before,after=cli("welcome-local-send","migration:welcome",welcome)
            check("welcome writes one durable sent marker",js('db.accounts.countDocuments({welcomeSentAt:{$ne:null}})')==1 and "SENT=1" in result.stdout,{})
            result,before,after=cli("welcome-repeat","migration:welcome",welcome)
            check("new-process welcome repeat does not resend",before==after and "ALREADY_SENT=1" in result.stdout,{})
            failed=["--club=canic","--only-numbers="+str(numbers[1]),"--interval=1s"]
            result,before,after=cli("welcome-mailbox-failure","migration:welcome",failed,1,mailbox="/dev/null/unwritable")
            check("failed delivery remains claimed for review","REVIEW_REQUIRED=1" in result.stdout and js('db.accounts.countDocuments({welcomeDeliveryId:{$ne:null},welcomeSentAt:null})')==1,{})
            result,before,after=cli("welcome-uncertain-repeat","migration:welcome",failed,1)
            check("uncertain repeat cannot silently resend",before==after and "REVIEW_REQUIRED=1" in result.stdout,{})
            result,before,after=cli("welcome-other-tenant","migration:welcome",["--club=minim","--only-numbers="+str(numbers[0]),"--dry-run"])
            check("welcome tenant isolation",before==after and "DRY RUN {}" in result.stdout,{})
            ttl=js('db.magic_link_tokens.find({purpose:"WELCOME"},{createdAt:1,expiresAt:1}).toArray().map(x=>(x.expiresAt-x.createdAt)/1000)')
            check("actual welcome token has seven-day TTL",bool(ttl) and all(value==604800 for value in ttl),{"seconds":ttl})
            check("other tenant business untouched",js(f'db.members.countDocuments({{clubId:{json.dumps(other)}}})')==0,{})
        finally:
            for name in active:
                shell(["docker","rm","-f",name],check=False)
            shell(["docker","rm","-f",mongo],check=False)
            shell(["docker","network","rm",network],check=False)
            (out / "cleanup.json").write_text(json.dumps({"ownedContainersRemoved":all(shell(["docker","container","inspect",name],check=False).returncode!=0 for name in [mongo,*active]),
                "networkRemoved":shell(["docker","network","inspect",network],check=False).returncode!=0},indent=2)+"\n")
    print("RESULT "+json.dumps({"checks":len(cases),"passed":sum(case["passed"] for case in cases)}),flush=True)
    return 0 if all(case["passed"] for case in cases) else 1


if __name__=="__main__":
    raise SystemExit(main())
