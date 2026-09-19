#!/usr/bin/env python3
"""Disposable real-HTTP S07 rehearsal. Credentials stay in process memory."""
import base64, datetime, hashlib, json, os, pathlib, secrets, socket, subprocess, tempfile, time, uuid
ROOT=pathlib.Path(__file__).resolve().parents[3]
BASE=ROOT/'roadmap/evidence/E4-T04'
name='agilityhub-e4-t04-'+str(os.getpid())
def run(args,**kw):
    result=subprocess.run(args,text=True,capture_output=True,**kw)
    if result.returncode:
        raise RuntimeError(str(args[:2])+': '+clean(result.stdout+result.stderr))
    return result.stdout

def mongo(script):
    return run(['docker','exec','-i',name,'mongosh','--quiet','agilityhub_e4_t04','--eval',script])

def port():
    with socket.socket() as s:
        s.bind(('127.0.0.1',0));return s.getsockname()[1]

def clean(text):
    import re
    text=re.sub(r'eyJ[A-Za-z0-9_.-]+','eyJ...[truncated]',text)
    text=re.sub(r'\b[0-9a-f]{24,}\b','[hash truncated]',text)
    for secret in secret_values:
        text=text.replace(secret,'[truncated]')
    return text

secret_values=[]
app=None
try:
    run(['docker','run','-d','--rm','--name',name,'-p','127.0.0.1::27017','mongo:7','--replSet','rs0','--bind_ip_all'])
    for _ in range(60):
        try:
            mongo('try {rs.initiate({_id:"rs0",members:[{_id:0,host:"localhost:27017"}]})} catch(e) {}');break
        except RuntimeError:time.sleep(1)
    for _ in range(60):
        if 'true' in mongo('print(db.hello().isWritablePrimary)'):break
        time.sleep(1)
    dbport=run(['docker','port',name,'27017/tcp']).strip().rsplit(':',1)[1]
    env=os.environ.copy();password=secrets.token_urlsafe(24);master=base64.b64encode(secrets.token_bytes(32)).decode();key=secrets.token_urlsafe(32)
    secret_values.extend([password,master,key]);apiport=port();management=port()
    env.update(MONGODB_PORT=dbport,MONGODB_DATABASE='agilityhub_e4_t04',MONGODB_REPLICA_SET='rs0',MONGODB_HOST='127.0.0.1',SEED_PASSWORD=password,
               OIDC_MASTER_KEY=master,SERVER_PORT=str(apiport),MANAGEMENT_SERVER_PORT=str(management),SPRING_PROFILES_ACTIVE='local',
               MAIL_LOCAL_DIRECTORY=tempfile.mkdtemp(prefix='e4-t04-mail-'),CORE_SECURITY_RATELIMITS_ENABLED='false')
    for secret in ['SENDGRID_API_KEY','SENDGRID_WEBHOOK_PUBLIC_KEY','MIGRATION_BANK_KEY']:env.pop(secret,None)
    for args in [['club:apply','seeds/club-canic.yaml'],['identity:seed-test-accounts','--club=canic','--allow-seed-passwords']]:
        output=run(['bin/core',*args],cwd=ROOT,env=env);print('bin/core '+' '.join(args)+' -> exit 0',flush=True)
    day=datetime.date.today()+datetime.timedelta(days=7)
    while day.weekday()!=0:day+=datetime.timedelta(days=1)
    day=day.isoformat();today=datetime.date.today().isoformat()
    script='''const c=db.clubs.findOne({slug:"canic"});
    db.clubs.updateOne({_id:c._id},{$set:{status:"ACTIVE",publicApiKeyHash:KEYHASH,websiteUrl:"https://example.test"}});
    for(const [index,email] of ["member@example.test","member.2@example.test"].entries()) {
      const a=db.accounts.findOne({email});const member="smoke-member-"+index;
      db.memberships.updateOne({clubId:c._id,accountId:a._id},{$set:{memberId:member,status:"ACTIVE"}});
      db.members.insertOne({_id:member,clubId:c._id,accountId:a._id,memberNumber:900+index,firstName:"Example",lastName1:"Member "+index,status:"ACTIVE",bookingBlock:{active:false},contactEmails:[{email}],phones:[{prefix:"+34",number:"600000001"}],version:0});
    }
    const r=db.rings.findOne({clubId:c._id,active:true});
    db.class_sessions.insertOne({_id:"smoke-class",clubId:c._id,date:DAY,startTime:"18:00",endTime:"19:00",startsAt:new Date(DAY+"T16:00:00Z"),endsAt:new Date(DAY+"T17:00:00Z"),ringId:r._id,state:"ACTIVE",levelIds:[],instructorIds:[],capacity:5,capacityMode:"MANUAL",counters:{booked:0,waiting:0},version:NumberLong(0)});
    print(JSON.stringify({clubId:c._id,ringId:r._id}));'''.replace('KEYHASH',json.dumps(hashlib.sha256(key.encode()).hexdigest())).replace('DAY',json.dumps(day))
    fixture=json.loads(mongo(script).strip().splitlines()[-1])
    jar=next((ROOT/'target').glob('agilityhub-core-api-*.jar'))
    app_log=open('/tmp/e4-t04-smoke-app.log','w')
    app=subprocess.Popen(['java','-jar',str(jar)],cwd=ROOT,env=env,stdout=app_log,stderr=subprocess.STDOUT)
    url='http://127.0.0.1:'+str(apiport)
    for _ in range(90):
        result=subprocess.run(['curl','-fsS',url+'/api/v1/health'],capture_output=True)
        if result.returncode==0:break
        if app.poll() is not None:raise RuntimeError('HTTP server exited')
        time.sleep(1)
    else:raise RuntimeError('HTTP startup timed out')
    def curl(method,path,body=None,token=None,expected=200,api_key=False,form=False):
        args=['curl','-sS','-X',method,url+path,'-H','Host: app.agilitycanic.cat','-w','\n%{http_code}']
        if token:args+=['-H','Authorization: Bearer '+token]
        if api_key:args+=['-H','X-Api-Key: '+key]
        if method=='POST' and not form:args+=['-H','Idempotency-Key: '+str(uuid.uuid4())]
        if body is not None:args+=['-H','Content-Type: '+('application/x-www-form-urlencoded' if form else 'application/json'),'--data-binary','@-']
        output=run(args,input=(body if form else json.dumps(body)) if body is not None else None)
        text,status=output.rsplit('\n',1)
        if int(status)!=expected:raise AssertionError(f'{method} {path} -> {status}: {clean(text)}')
        print(f'curl -X {method} {path}'+(' (credentials masked)' if token or api_key or form else '')+f' -> {status}',flush=True)
        return json.loads(text) if text else None
    import urllib.parse
    tokens=[]
    for email in ['admin@example.test','member@example.test','member.2@example.test']:
        response=curl('POST','/oauth2/token',urllib.parse.urlencode({'grant_type':'password','client_id':'clubs-app','username':email,'password':password}),form=True)
        tokens.append(response['access_token']);secret_values.append(tokens[-1])
    admin,one,two=tokens
    a=curl('POST','/api/v1/activities',{'title':{'ca':'Activitat de prova'},'type':'SEMINAR'},admin,201);id=a['id']
    a=curl('PATCH','/api/v1/activities/'+id,{'version':a['version'],'date':day,'startTime':'18:00','endTime':'20:00','registrationFrom':today,'registrationTo':day,'ringIds':[fixture['ringId']],'maxPlaces':1,'waitlistEnabled':True},admin)
    failure=curl('POST','/api/v1/activities/'+id+'/publication',{},admin,409);assert failure['code']=='RING_BLOCK_CONFLICT'
    a=curl('POST','/api/v1/activities/'+id+'/publication',{'cancelClasses':True,'adminText':'Example event replaces class','notifyEmail':True},admin)
    r1=curl('POST','/api/v1/activity-registrations',{'activityId':id},one,201)
    r2=curl('POST','/api/v1/activity-registrations',{'activityId':id,'joinWaitlist':True},two,201);assert r2['state']=='WAITLISTED'
    curl('POST','/api/v1/activity-registrations/'+r1['id']+'/cancellation',{},one)
    assert curl('GET','/api/v1/activity-registrations/'+r2['id'],token=two)['state']=='ACTIVE'
    a=curl('GET','/api/v1/activities/'+id,token=admin)
    curl('PATCH','/api/v1/activities/'+id,{'version':a['version'],'startTime':'18:30'},admin)
    public=curl('GET','/api/v1/public/canic/activities',api_key=True)
    assert len(public['items'])==1 and all(word not in json.dumps(public) for word in ['memberId','registrations','phones'])
    print('Public privacy allowlist: PASS',flush=True)
    # Let durable N-32d consume before exercising cancellation.
    time.sleep(3)
    curl('POST','/api/v1/activities/'+id+'/cancellation',{'reason':'CLUB_MANUAL','adminText':'Example weather cancellation'},admin)
    time.sleep(3)
    rows=mongo('print(JSON.stringify({events:db.domain_events.distinct("type"),notifications:db.notifications.aggregate([{$group:{_id:{code:"$code",channel:"$channel",status:"$status"},count:{$sum:1}}},{$sort:{"_id.code":1,"_id.channel":1}}]).toArray()}))')
    evidence=json.loads(rows.strip().splitlines()[-1]);print(json.dumps(evidence,indent=2),flush=True)
    assert {'ActivityPublished','ActivityUpdated','ActivityRegistrationChanged','ActivityCancelled','ClassCancelledByClub'}.issubset(evidence['events'])
    assert {'APP','EMAIL','SMS'}.issubset({r['_id']['channel'] for r in evidence['notifications'] if r['_id']['code']=='N-32c'})
    output=run(['bin/core','activities:finish-ended','--club=canic'],cwd=ROOT,env=env)
    (BASE/'05-finish-ended.log').write_text(clean(output));print('bin/core activities:finish-ended --club=canic -> exit 0',flush=True)
    print('S07 disposable HTTP rehearsal: PASS',flush=True)
finally:
    if app is not None:
        app.terminate()
        try:app.wait(timeout=15)
        except subprocess.TimeoutExpired:app.kill();app.wait()
    subprocess.run(['docker','rm','-f',name],capture_output=True)
