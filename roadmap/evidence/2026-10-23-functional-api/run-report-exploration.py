#!/usr/bin/env python3
"""Exact-image CLI proof with isolated Mongo and LocalStack; fake S3 PUT outage only."""
import base64
import copy
import csv
from collections import Counter
import datetime
import hashlib
import io
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import zipfile

ROOT = Path(__file__).resolve().parents[3]
EVIDENCE = Path(__file__).resolve().parent
IMAGE = "agilityhub-core-api:review-20261023"
EXPECTED = "sha256:1e8060615d372dc826ea431e1cd9ef3733c440d7ce4ecc02199f708e9c6d752e"
BUSINESS = ["members","dogs","family_groups","accounts","memberships","invoices","pack_balances",
            "notifications","magic_link_tokens","audit_entries","domain_events"]
BUCKET = "fictional-migration-reports"
PROXY = '''from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
from http.client import HTTPConnection
import json
class Handler(BaseHTTPRequestHandler):
 def log_message(self,*args): pass
 def do(self):
  size=int(self.headers.get('Content-Length','0')); body=self.rfile.read(size) if size else None
  if self.command=='PUT' and 'migration-reports/' in self.path and Path('/tmp/deny-report-put').exists():
   data=b'<Error><Code>AccessDenied</Code><Message>Fictional report outage</Message></Error>'
   self.send_response(403); self.send_header('Content-Type','application/xml'); self.send_header('Content-Length',str(len(data))); self.end_headers(); self.wfile.write(data)
   with open('/tmp/report-fault-events.jsonl','a') as log: log.write(json.dumps({'operation':'PutObject','status':403,'fictionalOutage':True})+'\\n')
   return
  conn=HTTPConnection('127.0.0.1',4566,timeout=40)
  headers={k:v for k,v in self.headers.items() if k.lower() not in ('connection','expect','transfer-encoding')}
  conn.request(self.command,self.path,body,headers); r=conn.getresponse(); data=r.read()
  self.send_response(r.status)
  for k,v in r.getheaders():
   if k.lower() not in ('transfer-encoding','connection','content-length'): self.send_header(k,v)
  self.send_header('Content-Length',str(len(data))); self.end_headers()
  if self.command!='HEAD': self.wfile.write(data)
  conn.close()
 do_GET=do; do_HEAD=do; do_PUT=do; do_DELETE=do; do_POST=do
ThreadingHTTPServer(('0.0.0.0',4567),Handler).serve_forever()
'''

def run(args, *, check=True, timeout=180, input=None, text=True):
    result=subprocess.run(args,cwd=ROOT,capture_output=True,text=text,input=input,timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f"{args[0]} {args[1]} exited {result.returncode}")
    return result

def manifest_digest(manifest):
    data=bytearray()
    def string(value):
        b=value.encode(); data.extend(struct.pack('>i',len(b))); data.extend(b)
    string('agilityhub-migration-manifest-v1'); string(manifest['mappingSha256'])
    files=sorted(manifest['files'],key=lambda f:f['name']); data.extend(struct.pack('>i',len(files)))
    for f in files:
        string(f['name']); string(f['sha256']); data.extend(struct.pack('>i',f['rows']))
    return hashlib.sha256(data).hexdigest()

def main():
    os.umask(0o077)
    attempt=int(sys.argv[1]) if len(sys.argv)>1 else 1
    out=EVIDENCE/f'report-attempt-{attempt:02d}'; out.mkdir(exist_ok=False)
    image=json.loads(run(['docker','image','inspect',IMAGE]).stdout)[0]; assert image['Id']==EXPECTED
    project='fictional-report-'+secrets.token_hex(4); network=project+'-net'; mongo=project+'-mongo'; s3=project+'-s3'
    active=[]; cases=[]; serial=0
    def check(label,condition,detail=None):
        cases.append({'case':label,'passed':bool(condition),'detail':detail or {}})
        (out/'results.json').write_text(json.dumps(cases,indent=2)+'\n')
        print(('PASS ' if condition else 'FAIL ')+label,flush=True)
    def js(expression):
        result=run(['docker','exec',mongo,'mongosh','--quiet','functional','--eval','JSON.stringify('+expression+')'])
        return json.loads(result.stdout.strip())
    def snapshot():
        values=js('Object.fromEntries('+json.dumps(BUSINESS+['migration_runs'])+'.map(n=>[n,db.getCollection(n).find({}).sort({_id:1}).toArray()]))')
        return {name:{'count':len(rows),'sha256':hashlib.sha256(json.dumps(rows,sort_keys=True).encode()).hexdigest()} for name,rows in values.items()}
    def s3py(code):
        return run(['docker','exec',s3,'python','-c',"import boto3,json,base64; c=boto3.client('s3',endpoint_url='http://127.0.0.1:4566',region_name='us-east-1',aws_access_key_id='test',aws_secret_access_key='test'); "+code])
    with tempfile.TemporaryDirectory(prefix='agilityhub-private-report-') as folder:
        work=Path(folder); fixture=work/'input'; shutil.copytree(ROOT/'src/test/resources/fixtures/playoff',fixture)
        fixture.chmod(0o755)
        for p in fixture.iterdir():
            if p.is_file(): p.chmod(0o644)
        approval=work/'approval.json'; approval.write_text('{}'); approval.chmod(0o600)
        # Host owner differs from the image UID. Copy the 0600 file into each command container then chown to10001.
        env={'SPRING_PROFILES_ACTIVE':'staging','MONGODB_HOST':mongo,'MONGODB_DATABASE':'functional',
             'MONGODB_REPLICA_SET':'rs0','MONGODB_AUTH_DATABASE':'admin','MONGODB_USERNAME':'fictionalCore',
             'MONGODB_PASSWORD':secrets.token_urlsafe(28),'AUTH_ISSUER':'https://id.example.test',
             'OIDC_LOGIN_URL':'https://id.example.test/login','OIDC_LEARN_CLIENT_SECRET':secrets.token_urlsafe(32),
             'TRUSTED_PROXY_PATTERN':r'127\.0\.0\.1','MAIL_FROM_PLATFORM':'no-reply@example.test',
             'SEED_PASSWORD':'Aa9!'+secrets.token_urlsafe(28),'SHARED_SCHEDULING_ENABLED':'false'}
        for key in ['OIDC_MASTER_KEY','BILLING_BANK_KEY','BILLING_SECRETS_KEY','SIGNUP_CAPABILITY_KEY','BOOKING_CALENDAR_KEY','EMAIL_UNSUBSCRIBE_KEY']:
            env[key]=base64.b64encode(secrets.token_bytes(32)).decode()
        private=run(['openssl','ecparam','-name','prime256v1','-genkey','-noout'],text=False).stdout
        der=run(['openssl','ec','-outform','DER'],input=private,text=False).stdout
        pub=run(['openssl','ec','-pubout','-outform','DER'],input=private,text=False).stdout
        assert der[5:7]==b'\x04\x20' and pub[-65]==4
        env.update(VAPID_PRIVATE_KEY=base64.urlsafe_b64encode(der[7:39]).decode().rstrip('='),
                   VAPID_PUBLIC_KEY=base64.urlsafe_b64encode(pub[-65:]).decode().rstrip('='),VAPID_SUBJECT='mailto:operator@example.test',
                   SENDGRID_WEBHOOK_PUBLIC_KEY=base64.b64encode(pub).decode(),SENDGRID_API_KEY='fictional-'+secrets.token_hex(24),
                   TWILIO_ACCOUNT_SID='AC'+secrets.token_hex(16),TWILIO_AUTH_TOKEN=secrets.token_hex(16))
        envfile=work/'runtime.env'
        def cli(label,command,args,expected=0,with_approval=False):
            nonlocal serial
            serial+=1; name=project+'-cmd-'+str(serial); active.append(name)
            invocation=['docker','create','--name',name,'--network',network,'--env-file',str(envfile),
                        '--mount',f'type=bind,src={fixture},dst=/private-input,readonly',IMAGE,'--core.command='+command,*args]
            run(invocation)
            if with_approval:
                # docker cp preserves source uid by default only with -a; use tar owned by image user for a private readable file.
                import tarfile
                tar=io.BytesIO()
                with tarfile.open(fileobj=tar,mode='w') as archive:
                    data=approval.read_bytes(); info=tarfile.TarInfo('approval.json'); info.size=len(data); info.mode=0o600; info.uid=10001; info.gid=10001; archive.addfile(info,io.BytesIO(data))
                run(['docker','cp','-a','-',name+':/tmp'],input=tar.getvalue(),text=False)
            before=snapshot(); result=run(['docker','start','-a',name],check=False,timeout=300); after=snapshot()
            # docker start -a sometimes returns0 even for container failure; use the actual process status.
            actual=int(run(['docker','inspect',name,'--format','{{.State.ExitCode}}']).stdout.strip())
            stem=out/f'{serial:02d}-{label}'
            stem.with_suffix('.command').write_text(shlex.join(invocation)+'\n'+shlex.join(['docker','start','-a',name])+'\n')
            stem.with_suffix('.exit').write_text(str(actual)+'\n')
            log=result.stdout+result.stderr
            for value in env.values():
                if len(value)>25: log=log.replace(value,'[ephemeral configuration redacted]')
            log=re.sub(r'(?<![a-zA-Z0-9])[a-f0-9]{64}(?![a-zA-Z0-9])','[manifest SHA-256 redacted]',log)
            stem.with_suffix('.log').write_text(log)
            summary={label:{k:{'count':v['count'],'sha256Prefix':v['sha256'][:16]} for k,v in snap.items()} for label,snap in {'before':before,'after':after}.items()}
            stem.with_name(stem.name+'-state.json').write_text(json.dumps(summary,indent=2)+'\n')
            run(['docker','rm',name]); active.remove(name)
            check(label+' exit',actual==expected,{'expected':expected,'actual':actual})
            if label.startswith('seed-') and actual: raise RuntimeError('Seed fixture failed; inspect saved log')
            return result,before,after
        try:
            run(['docker','network','create','--internal',network])
            run(['docker','run','-d','--name',mongo,'--network',network,'mongo:7','mongod','--replSet','rs0','--bind_ip_all'])
            run(['docker','run','-d','--name',s3,'--network',network,'-e','SERVICES=s3','-e','EAGER_SERVICE_LOADING=1','-e','DISABLE_EVENTS=1','-e','SKIP_INFRA_DOWNLOADS=1','localstack/localstack:3.8.1'])
            for _ in range(90):
                if run(['docker','exec',mongo,'mongosh','--quiet','--eval','db.adminCommand({ping:1})'],check=False).returncode==0: break
                time.sleep(1)
            run(['docker','exec',mongo,'mongosh','--quiet','--eval',f'rs.initiate({{_id:"rs0",members:[{{_id:0,host:"{mongo}:27017"}}]}})'])
            for _ in range(60):
                if js('db.hello().isWritablePrimary'): break
                time.sleep(1)
            print('SETUP Mongo replica ready',flush=True)
            js('db.getSiblingDB("admin").createUser('+json.dumps({'user':env['MONGODB_USERNAME'],'pwd':env['MONGODB_PASSWORD'],'roles':[{'role':'dbOwner','db':'functional'}]})+')')
            for _ in range(90):
                if run(['docker','exec',s3,'awslocal','s3api','list-buckets'],check=False).returncode==0: break
                time.sleep(1)
            print('SETUP LocalStack S3 ready',flush=True)
            s3py("c.create_bucket(Bucket="+repr(BUCKET)+"); c.put_public_access_block(Bucket="+repr(BUCKET)+",PublicAccessBlockConfiguration=dict(BlockPublicAcls=True,IgnorePublicAcls=True,BlockPublicPolicy=True,RestrictPublicBuckets=True)); c.put_bucket_lifecycle_configuration(Bucket="+repr(BUCKET)+",LifecycleConfiguration={'Rules':[{'ID':'migration-reports','Status':'Enabled','Filter':{'Prefix':'migration-reports/'},'Expiration':{'Days':30}}]})")
            ip=json.loads(run(['docker','inspect',s3]).stdout)[0]['NetworkSettings']['Networks'][network]['IPAddress']
            proxy=work/'proxy.py'; proxy.write_text(PROXY); run(['docker','cp',str(proxy),s3+':/tmp/report-proxy.py'])
            run(['docker','exec','-d',s3,'python','/tmp/report-proxy.py'])
            for prefix in ['EXPORT_S3','ATTACHMENT_S3']:
                env.update({prefix+'_BUCKET':BUCKET,prefix+'_REGION':'us-east-1',prefix+'_ENDPOINT':f'http://{ip}:4567',prefix+'_ACCESS_KEY':'test',prefix+'_SECRET_KEY':'test'})
            envfile.write_text(''.join(k+'='+v+'\n' for k,v in env.items())); envfile.chmod(0o600)
            (out/'image.json').write_text(json.dumps({'tag':IMAGE,'id':EXPECTED,'profile':'staging','commandEnvironment':'production','network':'internal; no published ports or real providers',
                's3EmulatorImage':json.loads(run(['docker','image','inspect','localstack/localstack:3.8.1']).stdout)[0]['Id']},indent=2)+'\n')
            config=json.loads(s3py("print(json.dumps({'privacy':c.get_public_access_block(Bucket="+repr(BUCKET)+")[\"PublicAccessBlockConfiguration\"],'versioning':c.get_bucket_versioning(Bucket="+repr(BUCKET)+").get('Status'),'lifecycle':c.get_bucket_lifecycle_configuration(Bucket="+repr(BUCKET)+")[\"Rules\"]}))").stdout)
            (out/'bucket-policy.json').write_text(json.dumps(config,indent=2)+'\n')
            check('strict bucket preconditions',all(config['privacy'].values()) and config['versioning'] is None and config['lifecycle'][0]['Expiration']['Days']==30)
            cli('seed-canic','club:apply',['seeds/club-canic.yaml','--allow-seed-passwords'])
            club=js('db.clubs.findOne({slug:"canic"})._id')
            common=['--club=canic','--in=/private-input','--cut-over=2026-09-01','--steps=1-10','--env=production','--confirm-production']
            result,before,after=cli('production-dry','migration:playoff',common+['--dry-run'])
            check('production dry-run exact zero writes',before==after)
            run(['docker','exec',s3,'touch','/tmp/deny-report-put'])
            result,before,after=cli('production-report-outage','migration:playoff',common+['--apply'],expected=1)
            current=js('db.migration_runs.findOne({env:"PRODUCTION"})')
            check('report outage leaves completed migration',current is not None and current['status'] in ['COMPLETED','RECONCILED'] and current.get('reportFileKey') is None and js('db.members.countDocuments({})')==187 and js('db.dogs.countDocuments({})')==189)
            if current is None: raise RuntimeError('Production run did not start; inspect saved log')
            check('recovery guidance says report only','recover only the report with migration:report' in result.stderr and 'Do not reapply' in result.stderr)
            faults=run(['docker','exec',s3,'cat','/tmp/report-fault-events.jsonl']).stdout; (out/'fault-events.jsonl').write_text(faults)
            check('outage reached actual S3 PutObject',bool(faults.strip()))
            run(['docker','exec',s3,'rm','/tmp/deny-report-put'])
            result,before,after=cli('report-only-recovery','migration:report',['--club=canic','--run='+current['_id'],'--in=/private-input'])
            recovered=js('db.migration_runs.findOne({_id:'+json.dumps(current['_id'])+'})')
            check('recovery changes no business documents',all(before[k]==after[k] for k in BUSINESS) and before['migration_runs']['count']==after['migration_runs']['count'])
            check('recovery publishes existing run pointer',recovered.get('reportFileKey')=='migration-reports/'+club+'/'+current['_id']+'/report.xlsx')
            key=recovered.get('reportFileKey')
            if not key: raise RuntimeError('Report pointer missing after recovery; inspect log')
            metadata=json.loads(s3py("r=c.head_object(Bucket="+repr(BUCKET)+",Key="+repr(key)+"); print(json.dumps({k:r.get(k) for k in ['ContentLength','ContentType','ContentDisposition','CacheControl','ServerSideEncryption','VersionId']}))").stdout)
            (out/'report-object.json').write_text(json.dumps(metadata,indent=2)+'\n')
            workbook=base64.b64decode(s3py("print(base64.b64encode(c.get_object(Bucket="+repr(BUCKET)+",Key="+repr(key)+")['Body'].read()).decode())").stdout)
            (out/'recovered-fictional-report.xlsx').write_bytes(workbook)
            with zipfile.ZipFile(io.BytesIO(workbook)) as archive:
                check('recovered XLSX valid archive',archive.testzip() is None and 'xl/workbook.xml' in archive.namelist())
            check('report encrypted private unversioned object',metadata['ServerSideEncryption']=='AES256' and metadata['VersionId'] is None and metadata['CacheControl']=='private, no-store')
            expires=datetime.datetime.fromisoformat(recovered['reportExpiresAt'].replace('Z','+00:00')); finished=datetime.datetime.fromisoformat(recovered['finishedAt'].replace('Z','+00:00'))
            check('report retention30 days',(expires-finished).total_seconds()==30*86400)
            result,before,after=cli('report-only-repeat','migration:report',['--club=canic','--run='+current['_id'],'--in=/private-input'])
            check('repeated report recovery exact zero writes',before==after)
            result,before,after=cli('unapproved-production-repeat','migration:playoff',common+['--apply'],expected=1)
            check('production repeat requires approval before writes',before==after and 'MIGRATION_ALREADY_APPLIED' in result.stderr)
            # Change only a fictional mapped person name; approval binds the new input bytes and the prior attempt.
            csv_path=fixture/'socis.csv'
            delimiter=';' if ';' in csv_path.read_text().splitlines()[0] else ','
            with csv_path.open(newline='',encoding='utf-8-sig') as stream:
                rows=list(csv.reader(stream,delimiter=delimiter))
            name_column=rows[0].index('Nom'); source_column=rows[0].index('ID associat')
            document_column=rows[0].index('NIF'); status_column=rows[0].index('Estat')
            documents=Counter(row[document_column] for row in rows[1:])
            selected=next(row for row in rows[1:] if row[status_column]=='Alta' and row[document_column] and documents[row[document_column]]==1)
            selected_source=selected[source_column]
            for row in rows[1:]:
                if row[source_column]==selected_source: row[name_column]='FunctionalReportName'
            with csv_path.open('w',newline='',encoding='utf-8') as stream:
                csv.writer(stream,delimiter=delimiter,lineterminator='\n').writerows(rows)
            candidate=copy.deepcopy(current['inputManifest'])
            for f in candidate['files']:
                if f['name']=='socis.csv': f['sha256']=hashlib.sha256(csv_path.read_bytes()).hexdigest()
            digest=manifest_digest(candidate)
            approved={'version':1,'clubId':club,'priorRunId':current['_id'],'inputManifestSha256':digest,'cutover':'2026-09-01',
                      'steps':list(range(1,11)),'approvedBy':'Fictional local operator','approvedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),
                      'reason':'Authorized isolated fixture repeat for functional review','runbookReference':'functional-api/report-only-local-fixture'}
            approval.write_text(json.dumps(approved)); approval.chmod(0o600)
            (out/'approval-binding.json').write_text(json.dumps({'clubId':club,'priorRunId':current['_id'],'cutover':approved['cutover'],'steps':approved['steps'],'inputManifestSha256Prefix':digest[:16],'fileMode':'0600','fileOwnerInContainer':10001,'approvedAt':approved['approvedAt']},indent=2)+'\n')
            result,before,after=cli('approved-reapply','migration:playoff',common+['--apply','--allow-reapply','--reapply-confirmation=/tmp/approval.json'],with_approval=True)
            latest=js('db.migration_runs.find({env:"PRODUCTION"}).sort({"execution.ordinal":-1}).limit(1).toArray()[0]')
            check('approved reapply adds immutable completed attempt',after['migration_runs']['count']==before['migration_runs']['count']+1 and latest['status'] in ['COMPLETED','RECONCILED'] and latest['execution']['priorRunId']==current['_id'] and latest.get('reportFileKey') is not None)
            check('approved reapply updates mapped fictional name',js('db.members.countDocuments({firstName:"FunctionalReportName"})')==1)
            check('approved repeat adds no duplicate census/accounts',all(before[k]['count']==after[k]['count'] for k in ['members','dogs','family_groups','accounts','memberships']))
            result,before,after=cli('stale-approval','migration:playoff',common+['--apply','--allow-reapply','--reapply-confirmation=/tmp/approval.json'],expected=1,with_approval=True)
            check('stale approval refuses before any writes',before==after and 'PRODUCTION_REQUIRES_CONFIRMATION' in result.stderr)
        finally:
            provider_log=run(['docker','logs','--tail','80',s3],check=False)
            (out/'localstack-tail.log').write_text(provider_log.stdout+provider_log.stderr)
            for name in active+[mongo,s3]: run(['docker','rm','-f',name],check=False)
            run(['docker','network','rm',network],check=False)
            (out/'cleanup.json').write_text(json.dumps({'ownedContainersRemoved':all(run(['docker','container','inspect',n],check=False).returncode!=0 for n in active+[mongo,s3]),'networkRemoved':run(['docker','network','inspect',network],check=False).returncode!=0},indent=2)+'\n')
    print('RESULT '+json.dumps({'checks':len(cases),'passed':sum(c['passed'] for c in cases)}),flush=True)
    return 0 if all(c['passed'] for c in cases) else 1
if __name__=='__main__': raise SystemExit(main())
