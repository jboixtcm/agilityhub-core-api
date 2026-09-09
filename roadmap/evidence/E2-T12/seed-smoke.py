import os,subprocess,time,secrets,json
from pathlib import Path
name='e2-t12-pages-smoke-'+secrets.token_hex(4)
evidence=Path('roadmap/evidence/E2-T12')
def run(args,**kwargs):return subprocess.run(args,check=True,text=True,capture_output=True,**kwargs)
try:
 run(['docker','run','-d','--name',name,'-p','127.0.0.1::27017','mongo:7','--replSet','rs0','--bind_ip_all'])
 for _ in range(60):
  result=subprocess.run(['docker','exec',name,'mongosh','--quiet','--eval','db.runCommand({ping:1}).ok'],text=True,capture_output=True)
  if result.returncode==0:break
  time.sleep(.5)
 run(['docker','exec',name,'mongosh','--quiet','--eval','rs.initiate({_id:"rs0",members:[{_id:0,host:"localhost:27017"}]})'])
 for _ in range(60):
  result=run(['docker','exec',name,'mongosh','--quiet','--eval','db.hello().isWritablePrimary'])
  if result.stdout.strip()=='true':break
  time.sleep(.5)
 else:raise RuntimeError('Disposable replica set did not elect a primary')
 port=run(['docker','port',name,'27017/tcp']).stdout.strip().rsplit(':',1)[1]
 env=os.environ.copy();env.update({'SPRING_PROFILES_ACTIVE':'local','MONGODB_HOST':'127.0.0.1','MONGODB_PORT':port,'MONGODB_DATABASE':'e2_t12_pages','MONGODB_REPLICA_SET':'rs0','SEED_PASSWORD':secrets.token_urlsafe(32),'SHARED_SCHEDULING_ENABLED':'false'})
 for number in (1,2):
  command=['bin/core','club:apply','seeds/club-canic.yaml']
  result=subprocess.run(command,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,env=env)
  (evidence/f'{9+number:02d}-seed-{number}.log').write_text(result.stdout)
  print(f'Command: {" ".join(command)} (run {number})\nExit code: {result.returncode}',flush=True)
  print('\n'.join(result.stdout.splitlines()[-40:]),flush=True)
  if result.returncode!=0:raise RuntimeError('Club seed failed')
  if number==2:
   assert '0 page changes' in result.stdout and '0 changes (applied)' in result.stdout
 result=run(['docker','exec',name,'mongosh','--quiet','e2_t12_pages','--eval','JSON.stringify(db.club_pages.find({}, {_id:0,key:1,version:1,active:1}).sort({key:1}).toArray())'])
 pages=json.loads(result.stdout.strip());assert len(pages)==3
 assert pages==[{'key':'IMAGE_CONSENT','version':1,'active':True},{'key':'PRIVACY','version':1,'active':False},{'key':'RULES','version':1,'active':True}]
 print('Persisted pages:',json.dumps(pages),flush=True)
finally:
 subprocess.run(['docker','rm','-f',name],check=False,capture_output=True)
 print('Disposable Mongo container removed.',flush=True)
