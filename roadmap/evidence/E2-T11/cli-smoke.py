import base64,json,os,re,secrets,socket,subprocess,tempfile,time,urllib.parse
from pathlib import Path
root=Path.cwd(); output=root/'roadmap/evidence/E2-T11'; output.mkdir(exist_ok=True,parents=True)
metadata=[]; runtime=Path(tempfile.mkdtemp(prefix='e2-t11-smoke-')); container='e2-t11-smoke-'+secrets.token_hex(4); server=None
seed_password=secrets.token_urlsafe(28); master=base64.b64encode(secrets.token_bytes(32)).decode(); access_token=''
env=dict(os.environ,SPRING_PROFILES_ACTIVE='local',SEED_PASSWORD=seed_password,OIDC_MASTER_KEY=master,MONGODB_DATABASE='seed_smoke',MONGODB_REPLICA_SET='rs0',ATTACHMENT_LOCAL_DIRECTORY=str(runtime/'attachments'),EXPORT_LOCAL_DIRECTORY=str(runtime/'exports'))
def sanitize(value):
 for secret in [seed_password,master,access_token]:
  if secret:value=value.replace(secret,'[truncated]')
 value=re.sub(r'([?&]signature=)[A-Za-z0-9_-]+',r'\1[truncated]',value)
 return re.sub(r'\b[0-9a-fA-F]{40,}\b',lambda m:m[0][:5]+'[truncated]',value)
def raw(cmd,**kwargs):return subprocess.run(cmd,text=True,capture_output=True,check=True,**kwargs).stdout

def step(number,name,cmd,expected=0,stdin=None):
 proc=subprocess.run(cmd,text=True,input=stdin,capture_output=True,env=env,timeout=90)
 text=sanitize(proc.stdout+proc.stderr); file=f'{number:02d}-{name}.log'; (output/file).write_text(text)
 command=sanitize(' '.join(__import__('shlex').quote(str(arg)) for arg in cmd))
 metadata.append(dict(command=command,exit_code=proc.returncode,log=file))
 print(command+' -> exit '+str(proc.returncode),flush=True); print('\n'.join(text.splitlines()[-5:]),flush=True)
 if proc.returncode!=expected:raise RuntimeError(f'{name} failed; see {file}')
 return proc.stdout

def snapshot():
 script="const d=db.getSiblingDB('seed_smoke'); print(EJSON.stringify(d.getCollectionNames().sort().map(n=>[n,d.getCollection(n).find().sort({_id:1}).toArray()])));"
 return raw(['docker','exec',container,'mongosh','--quiet','--eval',script]).strip()
try:
 raw(['docker','run','--rm','-d','--name',container,'-p','127.0.0.1::27017','mongo:7','--replSet','rs0','--bind_ip_all'])
 port=raw(['docker','port',container,'27017/tcp']).strip().split(':')[-1];env['MONGODB_PORT']=port
 for n in range(60):
  try:
   raw(['docker','exec',container,'mongosh','--quiet','--eval',"rs.initiate({_id:'rs0',members:[{_id:0,host:'localhost:27017'}]})"]);break
  except subprocess.CalledProcessError:time.sleep(.5)
 for n in range(60):
  try:
   result=raw(['docker','exec',container,'mongosh','--quiet','--eval','db.hello().isWritablePrimary'])
   if 'true' in result:break
  except subprocess.CalledProcessError:pass
  time.sleep(.5)
 print(f'Disposable Mongo: localhost:{port}/seed_smoke; local profile. Generated credentials remain private.',flush=True)
 step(7,'club-apply-first',['bin/core','club:apply','seeds/club-canic.yaml'])
 before=snapshot();second=step(8,'club-apply-second',['bin/core','club:apply','seeds/club-canic.yaml']);assert '0 changes (applied)' in second;assert snapshot()==before
 demo=step(9,'demo-first',['bin/core','seed:demo','--club=canic']);assert 'activeMembers=184' in demo and 'dogs=242' in demo
 before=snapshot();repeat=step(10,'demo-second',['bin/core','seed:demo','--club=canic','--seed=42']);assert '0 changes (demo seed)' in repeat;assert snapshot()==before
 print('Complete Mongo document snapshots unchanged after both second runs.',flush=True)
 with socket.socket() as sock:sock.bind(('127.0.0.1',0)); http_port=sock.getsockname()[1]
 env['SERVER_PORT']=str(http_port);env['MANAGEMENT_SERVER_PORT']='0';env['SHARED_SCHEDULING_ENABLED']='false'
 stdout=(runtime/'api.log').open('w'); jar=next((root/'target').glob('agilityhub-core-api-*.jar'))
 server=subprocess.Popen(['java','-jar',str(jar)],env=env,stdout=stdout,stderr=subprocess.STDOUT)
 base=f'http://127.0.0.1:{http_port}'
 for n in range(120):
  proc=subprocess.run(['curl','-fsS',base+'/api/v1/health'],capture_output=True)
  if proc.returncode==0:break
  if server.poll() is not None:raise RuntimeError(sanitize((runtime/'api.log').read_text()))
  time.sleep(.5)
 else:raise RuntimeError('API did not become healthy')
 login=raw(['curl','-fsS',base+'/oauth2/token','-H','Host: app.agilitycanic.cat','-H','Content-Type: application/x-www-form-urlencoded','--data-binary','@-'],input=urllib.parse.urlencode(dict(grant_type='password',client_id='clubs-app',username='admin@example.test',password=seed_password)))
 access_token=json.loads(login)['access_token']; config=f'header = "Host: app.agilitycanic.cat"\nheader = "Authorization: Bearer {access_token}"\n'
 step(11,'members-size-one',['curl','--fail-with-body','-sS','--config','-',base+'/api/v1/members?size=1'],expected=22,stdin=config)
 response=step(12,'members-active',['curl','--fail-with-body','-sS','--config','-',base+'/api/v1/members?size=20&filter=status:eq:ACTIVE&fields=id'],stdin=config)
 assert json.loads(response)['totalItems']==184
 response=step(13,'members-all',['curl','--fail-with-body','-sS','--config','-',base+'/api/v1/members?size=20&fields=id'],stdin=config)
 assert json.loads(response)['totalItems']==194
 dog=raw(['docker','exec',container,'mongosh','--quiet','--eval',"print(db.getSiblingDB('seed_smoke').dog_documents.findOne({state:'RECEIVED'}).dogId)"]).strip()
 response=step(17,'dog-documents',['curl','--fail-with-body','-sS','--config','-',base+'/api/v1/dogs/'+dog+'/documents'],stdin=config)
 url=next(row['files'][0]['url'] for row in json.loads(response) if row['state']=='RECEIVED')
 target=runtime/'download.pdf'
 step(18,'document-download',['curl','--fail-with-body','-sS','--config','-',base+url,'--output',str(target)],stdin=config)
 assert target.read_bytes().startswith(b'%PDF')
 print('PASS: active totalItems=184; all totalItems=194; 242 active dogs; tenant-authenticated HTTP; demo PDF downloaded.',flush=True)
finally:
 (output/'smoke-commands.json').write_text(json.dumps(metadata,indent=2)+'\n')
 if server:server.terminate();server.wait(timeout=20)
 subprocess.run(['docker','rm','-f',container],capture_output=True)
 import shutil;shutil.rmtree(runtime)
