import os,subprocess,secrets,tempfile,pathlib,shutil,time,json,socket
root=pathlib.Path(__file__).resolve().parents[3]
evidence=root/'roadmap/evidence/E2-T10'
# A disposable local Mongo, no access to an existing application database.
name='e2-t10-cli-'+secrets.token_hex(4)
def run(command,**kwargs):
 return subprocess.run(command,cwd=root,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,**kwargs)
with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
result=run(['docker','run','-d','--rm','--name',name,'-p',f'127.0.0.1:{port}:27017','mongo:7','--replSet','rs0','--bind_ip_all'])
if result.returncode:raise RuntimeError('Disposable Mongo start failed')
try:
 for _ in range(90):
  if run(['docker','exec',name,'mongosh','--quiet','--eval','db.adminCommand({ping:1})']).returncode==0:break
  time.sleep(.5)
 run(['docker','exec',name,'mongosh','--quiet','--eval',"rs.initiate({_id:'rs0',members:[{_id:0,host:'localhost:27017'}]})"])
 for _ in range(90):
  if run(['docker','exec',name,'mongosh','--quiet','--eval',"if(!db.hello().isWritablePrimary) quit(1)"]).returncode==0:break
  time.sleep(.5)
 env=os.environ.copy();env['SPRING_DATA_MONGODB_URI']=f'mongodb://127.0.0.1:{port}/e2_t10_cli?replicaSet=rs0&directConnection=true'
 env['OIDC_MASTER_KEY']=__import__('base64').b64encode(secrets.token_bytes(32)).decode()
 env['SHARED_SCHEDULING_ENABLED']='false'
 env['SEED_PASSWORD']=secrets.token_urlsafe(24)+'aA1!'
 seed=run(['bin/core','club:apply','seeds/club-canic.yaml'],env=env)
 (evidence/'10-cli-seed.log').write_text(seed.stdout)
 print('Disposable fictional club seed exit:',seed.returncode,flush=True)
 if seed.returncode:raise SystemExit(seed.returncode)
 def snapshot():
  script="let d=db.getSiblingDB('e2_t10_cli');print(EJSON.stringify(d.getCollectionNames().sort().map(n=>[n,d.getCollection(n).find().sort({_id:1}).toArray()])))"
  r=run(['docker','exec',name,'mongosh','--quiet','--eval',script]);r.check_returncode();return r.stdout
 before=snapshot()
 result=run(['bin/core','migration:playoff','src/test/resources/fixtures/playoff','--dry-run'],env=env)
 (evidence/'11-cli-dry-run.log').write_text(result.stdout)
 print('bin/core migration:playoff src/test/resources/fixtures/playoff --dry-run exit:',result.returncode,flush=True)
 after=snapshot()
 identical=before==after
 (evidence/'12-cli-no-writes.log').write_text(f'All Mongo collection names and full documents before/after CLI dry-run identical: {identical}\n')
 print('All Mongo collections/documents unchanged:',identical,flush=True)
 if result.returncode or not identical:raise SystemExit(1)
finally:
 cleanup=run(['docker','rm','-f',name]);print('Disposable Mongo cleanup exit:',cleanup.returncode,flush=True)
