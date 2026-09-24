"""E5-T03 verification helper (local stack). On a disposable Mongo 7 replica set (local profile, random loopback port):
`bin/core club:apply seeds/club-canic.yaml` + `bin/core seed:demo --club=canic --seed=42`, then the API jar on that database
and the S08 waiting-list curl sequence with the seeded member logins (member@…, member.2@… … member.10@…). In each
scenario the admin first sets the chosen demo class's capacity to its seeded bookings + 1 (`PATCH /class-sessions/{id}`,
D4), so one login booking fills it:
  ALL_AT_ONCE (Cànic default): fill the class → 3 members join → cancel a booking 5 h before → N-15 rows → the second
  waiting member holds through the offer and claims → the other two are demoted (N-46 rows) → one of them leaves;
  FIFO (API restarted with `waitlist.mode = FIFO`): fill a later class → 3 join → cancel → only the first is offered
  (confirmBy) → test clock +31 min, the entry is expired as S15 P6 would and a manual `WaitlistExpired` is written to the
  outbox → the second is offered → claims → the third leaves.
After every step it prints the outbox events and `notifications` rows the step produced. The test clock
(`POST /api/v1/test/clock`) is moved into the class's booking week first. Generated credentials and tokens stay
private; ids are truncated in every log."""
import base64, json, os, re, secrets, socket, subprocess, sys, tempfile, time, uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

root = Path.cwd()
runtime = Path(tempfile.mkdtemp(prefix='e5-t03-curl-')); container = 'e5-t03-curl-' + secrets.token_hex(4)
seed_password = secrets.token_urlsafe(28); master = base64.b64encode(secrets.token_bytes(32)).decode()
env = dict(os.environ, SPRING_PROFILES_ACTIVE='local', SEED_PASSWORD=seed_password, OIDC_MASTER_KEY=master,
           MONGODB_DATABASE='e5_waitlist', MONGODB_REPLICA_SET='rs0', SHARED_SCHEDULING_ENABLED='false', SERVER_PORT='0',
           MANAGEMENT_SERVER_PORT='0', ATTACHMENT_LOCAL_DIRECTORY=str(runtime / 'attachments'), EXPORT_LOCAL_DIRECTORY=str(runtime / 'exports'))
MADRID = ZoneInfo('Europe/Madrid'); HOST = 'app.agilitycanic.cat'
LOGINS = ['member@example.test'] + [f'member.{i}@example.test' for i in range(2, 11)]
tokens = []


def sanitize(value):
    for secret in [seed_password, master] + tokens:
        value = value.replace(secret, '[truncated]')
    value = re.sub(r'(token=)[A-Za-z0-9._-]+', r'\1[truncated]', value)
    value = re.sub(r'\b([0-9a-f]{8})-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b', r'\1…', value)
    return re.sub(r'\b[0-9a-fA-F]{40,}\b', lambda m: m[0][:5] + '[truncated]', value)


def say(text=''):
    print(sanitize(text), flush=True)


def raw(cmd, **kwargs):
    return subprocess.run(cmd, text=True, capture_output=True, check=True, **kwargs).stdout


def step(name, cmd):
    proc = subprocess.run(cmd, text=True, capture_output=True, env=env, timeout=900)
    text = sanitize(proc.stdout + proc.stderr)
    say(f"$ {' '.join(cmd)} -> exit {proc.returncode}")
    say('\n'.join('  ' + line for line in text.splitlines()[-6:]))
    if proc.returncode:
        raise RuntimeError(f'{name} failed')
    return proc.stdout


def mongo(script):
    return raw(['docker', 'exec', container, 'mongosh', '--quiet', '--eval', "const d=db.getSiblingDB('e5_waitlist');" + script]).strip()


def ids(collection):
    return set(json.loads(mongo(f"print(EJSON.stringify(d.{collection}.find({{}},{{_id:1}}).toArray().map(x=>x._id)))")))


def iso(instant):
    return instant.astimezone(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')


class Api:
    def __init__(self):
        port = socket.socket(); port.bind(('127.0.0.1', 0)); self.port = port.getsockname()[1]; port.close()
        api_env = dict(env, SERVER_PORT=str(self.port), SHARED_SCHEDULING_ENABLED='true', MAIL_LOCAL_DIRECTORY=str(runtime / 'mailbox'),
                       AUTH_ISSUER='https://id.example.test', OIDC_LOGIN_URL='https://id.example.test/login', LOGGING_LEVEL_ROOT='ERROR')
        jar = next(root.glob('target/agilityhub-core-api-*.jar'))
        self.log = open(runtime / 'api.log', 'a')
        self.process = subprocess.Popen(['java', '-jar', str(jar)], env=api_env, stdout=self.log, stderr=subprocess.STDOUT)
        self.base = f'http://127.0.0.1:{self.port}'; self.tokens = {}
        for _ in range(180):
            if subprocess.run(['curl', '-4', '-fsS', self.base + '/api/v1/health'], capture_output=True).returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError('API did not start')
        self.drain()

    def stop(self):
        self.process.terminate()
        try: self.process.wait(30)
        except subprocess.TimeoutExpired: self.process.kill()
        self.log.close()

    def drain(self):
        """Let the outbox deliver what is pending first, so each step shows only what it produced."""
        for _ in range(120):
            if mongo("print(d.domain_events.countDocuments({status:'PENDING'}))") == '0':
                break
            time.sleep(1)
        self.seen = ids('domain_events'); self.notes = ids('notifications')

    def call(self, method, path, expected, access=None, body=None, form=None, key=None, label=None):
        headers = ['Host: ' + HOST]
        if access: headers.append('Authorization: Bearer ' + access)
        if key: headers.append('Idempotency-Key: ' + key)
        args = ['curl', '-4', '--silent', '--show-error', '--max-time', '30', '--request', method, '--output', str(runtime / 'response'),
                '--write-out', '%{http_code}', '--config', '-']
        if body is not None or form is not None:
            payload = runtime / 'request'
            payload.write_text(json.dumps(body) if body is not None else '&'.join(f'{k}={v}' for k, v in form.items()))
            headers.append('Content-Type: ' + ('application/json' if body is not None else 'application/x-www-form-urlencoded'))
            args += ['--data-binary', '@' + str(payload)]
        config = '\n'.join('header = ' + json.dumps(h) for h in headers) + '\nurl = ' + json.dumps(self.base + path)
        result = subprocess.run(args, input=config, text=True, capture_output=True)
        status = int(result.stdout or 0); text = (runtime / 'response').read_text() if (runtime / 'response').exists() else ''
        value = json.loads(text) if text.startswith('{') else None
        if label:
            say(f"curl {method} {path.split('?')[0]} -> {status}" + (f" {value.get('code')}" if isinstance(value, dict) and 'code' in value and status >= 400 else '')
                + (f'   [{label}]' if label is not True else ''))
        if status != expected:
            raise AssertionError(sanitize(f'{method} {path}: expected {expected}, got {status} {text[:300]}'))
        return value

    def clock(self, instant):
        self.call('POST', '/api/v1/test/clock', 200, body={'instant': iso(instant)}, label='test clock')
        say(f'  test clock -> {iso(instant)} (club-local {instant.astimezone(MADRID):%a %d-%m %H:%M})')
        self.tokens = {}

    def token(self, email):
        if email not in self.tokens:
            value = self.call('POST', '/oauth2/token', 200, form=dict(grant_type='password', client_id='clubs-app', username=email, password=seed_password))['access_token']
            tokens.append(value); self.tokens[email] = value
        return self.tokens[email]

    def effects(self):
        time.sleep(4)  # the outbox dispatcher runs every second; N-15/N-46 come from consumers of consumers
        new_events = sorted(ids('domain_events') - self.seen); new_notes = sorted(ids('notifications') - self.notes)
        lines = []
        if new_events:
            lines += mongo(f"d.domain_events.find({{_id:{{$in:{json.dumps(new_events)}}}}}).sort({{occurredAt:1,_id:1}}).forEach(e=>print('  outbox '+e.type+' '+e.status+' '+e.aggregateId+' '+EJSON.stringify(e.payload)));").splitlines()
        if new_notes:
            lines += mongo(f"d.notifications.find({{_id:{{$in:{json.dumps(new_notes)}}}}}).sort({{code:1,channel:1,accountId:1}}).forEach(n=>print('  notification '+n.code+' '+n.channel+' '+n.status+' account '+(n.accountId||'-')+(n.variables?' '+EJSON.stringify(n.variables):'')+(n.body?' sms('+n.body.length+'): '+n.body:'')));").splitlines()
        self.seen |= set(new_events); self.notes |= set(new_notes)
        say('\n'.join(line if line.startswith('  ') else '  ' + line for line in lines) or '  (no outbox event or notification)')


def facts():
    return json.loads(mongo("""
const logins=%s.map(e=>{const a=d.accounts.findOne({email:e}); const m=a&&d.members.findOne({accountId:a._id,status:'ACTIVE'});
  const dogs=m?d.dogs.find({memberId:m._id,status:'ACTIVE'}).sort({_id:1}).toArray().map(g=>({dog:g._id,name:g.name,level:g.levelId||null})):[];
  return dogs.length?{email:e,member:m._id,dogs}:null}).filter(x=>x);
const classes=d.class_sessions.find({state:'ACTIVE'}).sort({startsAt:1}).toArray().map(c=>({id:c._id,startsAt:c.startsAt.toISOString(),date:c.date,
  time:c.startTime,capacity:c.capacity,booked:c.counters.booked,waiting:c.counters.waiting,levels:c.levelIds||[]}));
print(EJSON.stringify({clubId:d.members.findOne({_id:logins[0].member}).clubId,logins,classes}));""" % json.dumps(LOGINS)))


def pick(data, used, after=None):
    """A class without waiting entries whose level admits 4 login dogs not used yet: 1 booking (then cancelled) + 3 waiting.
    The admin sets its capacity to `booked + 1` through `PATCH /class-sessions/{id}` (D4), so one login booking fills it."""
    def eligible(c):
        """One dog per login member whose level the class admits (`levels.enabled`; an empty list admits every level)."""
        result = []
        for login in data['logins']:
            dog = next((g for g in login['dogs'] if g['level'] and (not c['levels'] or g['level'] in c['levels'])), None)
            if login['email'] not in used and dog:
                result.append({'email': login['email'], 'member': login['member'], **dog})
        return result
    for c in sorted(data['classes'], key=lambda c: (-c['booked'], c['startsAt'])):
        if after and c['startsAt'] <= after:
            continue
        candidates = eligible(c)
        if len(candidates) >= 4 and c['waiting'] == 0 and c['booked'] < c['capacity']:
            return c, candidates[:1], candidates[1:4]
    counts = [(c['date'], c['time'], len(eligible(c))) for c in data['classes'] if not after or c['startsAt'] > after]
    raise RuntimeError(f'no class with 4 eligible login dogs; (date, time, eligible) {counts}')


def scenario(api, mode, c, fillers, waiting):
    start = datetime.fromisoformat(c['startsAt'].replace('Z', '+00:00'))
    say(f"\n=== {mode}: class {c['date']} {c['time']} capacity {c['capacity']}, {c['booked']} seeded bookings, "
        f"{len(fillers)} login booking(s) to fill it, waiting: {', '.join(w['email'].split('@')[0] for w in waiting)}")
    api.clock(start - timedelta(hours=5))
    version = int(mongo(f"print(Number(d.class_sessions.findOne({{_id:{json.dumps(c['id'])}}}).version))"))
    patched = api.call('PATCH', f"/api/v1/class-sessions/{c['id']}", 200, access=api.token('admin@example.test'),
                       body={'capacity': c['booked'] + 1, 'version': version}, label='admin sets the capacity')
    say(f"  capacity {c['capacity']} -> {patched['capacity']} (seeded bookings {c['booked']})")
    api.effects()
    say('\n1. fill the class (hold + confirm)')
    bookings = []
    for f in fillers:
        held = api.call('POST', '/api/v1/seat-holds', 201, access=api.token(f['email']), body={'classSessionId': c['id'], 'dogId': f['dog']}, label=f['email'].split('@')[0])
        bookings.append((f, api.call('POST', '/api/v1/bookings', 201, access=api.token(f['email']), body={'seatHoldId': held['id']}, key=str(uuid.uuid4()), label='confirm')))
    say(mongo(f"const c=d.class_sessions.findOne({{_id:{json.dumps(c['id'])}}}); print('  class counters '+EJSON.stringify(c.counters)+' capacity '+c.capacity)"))
    api.effects()
    say('\n2. three members join the waiting list')
    entries = []
    for w in waiting:
        e = api.call('POST', '/api/v1/waitlist-entries', 201, access=api.token(w['email']), body={'classSessionId': c['id'], 'dogId': w['dog']}, label=w['email'].split('@')[0])
        entries.append((w, e)); say(f"  entry {e['id']} state={e['state']} position={e['position']} dog={e['dogName']}")
    say(mongo(f"const c=d.class_sessions.findOne({{_id:{json.dumps(c['id'])}}}); print('  class counters '+EJSON.stringify(c.counters))"))
    api.effects()
    say('\n3. a booked member cancels 5 h before (in time, > 30 min: the waiting list is told)')
    owner, booking = bookings[0]
    cancelled = api.call('POST', f"/api/v1/bookings/{booking['id']}/cancellation", 200, access=api.token(owner['email']), body={}, label=owner['email'].split('@')[0])
    say(f"  booking state={cancelled['state']} late={cancelled['cancellation']['late']} minutesBefore={cancelled['cancellation']['minutesBefore']}")
    api.effects()
    for w, e in entries:
        say(f"  {w['email'].split('@')[0]}: " + json.dumps({k: api.call('GET', f"/api/v1/waitlist-entries/{e['id']}", 200, access=api.token(w['email']))[k] for k in ('state', 'position', 'notifiedAt', 'confirmBy')}))
    if mode == 'FIFO':
        first, first_entry = entries[0]
        say('\n4. the offer of the first expires: test clock +31 min, the entry → EXPIRED as S15 P6 (E5-T05) does, manual WaitlistExpired in the outbox')
        now = start - timedelta(hours=5) + timedelta(minutes=31); api.clock(now)
        mongo(f"d.waitlist_entries.updateOne({{_id:{json.dumps(first_entry['id'])},state:'NOTIFIED'}},{{$set:{{state:'EXPIRED',updatedAt:new Date({json.dumps(iso(now))})}},$inc:{{version:NumberLong(1)}}}})")
        event_id = str(uuid.uuid4()); payload = {'entryId': first_entry['id'], 'classId': c['id']}
        envelope = {'kind': 'WaitlistExpired', 'clubId': data['clubId'], 'aggregateId': first_entry['id'], 'occurredAt': iso(now), 'payload': payload,
                    'actorAccountId': None, 'impersonatedMemberId': None, 'origin': 'SYSTEM'}
        mongo(f"d.domain_events.insertOne({{_id:{json.dumps(event_id)},clubId:{json.dumps(data['clubId'])},type:'WaitlistExpired',aggregateType:'WaitlistEntry',"
              f"aggregateId:{json.dumps(first_entry['id'])},occurredAt:new Date({json.dumps(iso(now))}),payload:{json.dumps(payload)},actorAccountId:null,"
              f"impersonatedMemberId:null,origin:'SYSTEM',eventJson:{json.dumps(json.dumps(envelope))},status:'PENDING',attempts:0,"
              f"nextAttemptAt:new Date({json.dumps(iso(now))}),publishedAt:null,error:null,claimToken:null,lockVersion:0,processedAt:{{}}}})")
        api.effects()
        for w, e in entries:
            say(f"  {w['email'].split('@')[0]}: " + json.dumps({k: api.call('GET', f"/api/v1/waitlist-entries/{e['id']}", 200, access=api.token(w['email']))[k] for k in ('state', 'notifiedAt', 'confirmBy')}))
        api.call('POST', '/api/v1/seat-holds', 422, access=api.token(first['email']), body={'classSessionId': c['id'], 'dogId': first['dog'], 'waitlistEntryId': first_entry['id']}, label='expired offer')
    claimer, claim_entry = entries[1]
    say(f"\n{5 if mode == 'FIFO' else 4}. {claimer['email'].split('@')[0]} takes the seat: hold through the offer → claim")
    if mode == 'ALL_AT_ONCE':
        say('  (the other two are NOTIFIED too; the first to hold and claim wins)')
    held = api.call('POST', '/api/v1/seat-holds', 201, access=api.token(claimer['email']), body={'classSessionId': c['id'], 'dogId': claimer['dog'], 'waitlistEntryId': claim_entry['id']}, label='hold')
    if mode == 'ALL_AT_ONCE':
        other, other_entry = entries[0]
        api.call('POST', '/api/v1/seat-holds', 409, access=api.token(other['email']), body={'classSessionId': c['id'], 'dogId': other['dog'], 'waitlistEntryId': other_entry['id']}, label='another waiting member while the seat is held')
    claimed = api.call('POST', f"/api/v1/waitlist-entries/{claim_entry['id']}/claim", 201, access=api.token(claimer['email']), body={'seatHoldId': held['id']}, key=str(uuid.uuid4()), label='claim')
    say(f"  booking {claimed['id']} state={claimed['state']} origin={claimed['origin']}")
    api.effects()
    for w, e in entries:
        say(f"  {w['email'].split('@')[0]}: " + json.dumps({k: api.call('GET', f"/api/v1/waitlist-entries/{e['id']}", 200, access=api.token(w['email']))[k] for k in ('state', 'notifiedAt', 'confirmBy', 'bookingId')}))
    say(mongo(f"const c=d.class_sessions.findOne({{_id:{json.dumps(c['id'])}}}); print('  class counters '+EJSON.stringify(c.counters))"))
    leaver, leave_entry = entries[2]
    say(f"\n{6 if mode == 'FIFO' else 5}. {leaver['email'].split('@')[0]} leaves the waiting list")
    left = api.call('POST', f"/api/v1/waitlist-entries/{leave_entry['id']}/cancellation", 200, access=api.token(leaver['email']), label='leave')
    say(f"  entry state={left['state']} cancelReason={left['cancelReason']}")
    api.effects()
    say(mongo(f"const c=d.class_sessions.findOne({{_id:{json.dumps(c['id'])}}}); print('  class counters '+EJSON.stringify(c.counters))"))


api = None
try:
    raw(['docker', 'run', '--rm', '-d', '--name', container, '-p', '127.0.0.1::27017', 'mongo:7', '--replSet', 'rs0', '--bind_ip_all'])
    env['MONGODB_PORT'] = raw(['docker', 'port', container, '27017/tcp']).strip().splitlines()[0].split(':')[-1]
    for _ in range(60):
        try:
            raw(['docker', 'exec', container, 'mongosh', '--quiet', '--eval', "rs.initiate({_id:'rs0',members:[{_id:0,host:'localhost:27017'}]})"]); break
        except subprocess.CalledProcessError:
            time.sleep(.5)
    for _ in range(60):
        if 'true' in raw(['docker', 'exec', container, 'mongosh', '--quiet', '--eval', 'db.hello().isWritablePrimary']):
            break
        time.sleep(.5)
    say('Disposable Mongo 7 replica set on a random localhost port, database e5_waitlist, local profile; generated credentials private.')
    step('club-apply', ['bin/core', 'club:apply', 'seeds/club-canic.yaml'])
    step('seed-demo', ['bin/core', 'seed:demo', '--club=canic', '--seed=42'])
    data = facts()
    say(f"{len(data['logins'])} member logins with an ACTIVE dog; {len(data['classes'])} ACTIVE classes; parameter waitlist.mode = "
        + mongo("const p=d.parameters.findOne({key:'waitlist.mode'}); print(p?p.value:'(catalog default ALL_AT_ONCE)')"))
    first_class, fillers, waiting = pick(data, set())
    used = {x['email'] for x in fillers + waiting}
    api = Api(); say('API jar up on a random loopback port (local profile); the seed outbox is drained')
    scenario(api, 'ALL_AT_ONCE', first_class, fillers, waiting)
    say('\n--- restart the API with waitlist.mode = FIFO (club parameter written before start, so the config cache starts fresh)')
    api.stop()
    mongo(f"d.parameters.deleteMany({{clubId:{json.dumps(data['clubId'])},key:'waitlist.mode'}}); d.parameters.insertOne({{_id:{json.dumps(str(uuid.uuid4()))},"
          f"clubId:{json.dumps(data['clubId'])},key:'waitlist.mode',value:'FIFO',type:'enum',scope:'club',scopeRef:null,history:[],version:NumberLong(0),updatedAt:new Date()}})")
    data = facts()
    second_class, fillers, waiting = pick(data, used, after=first_class['startsAt'])
    api = Api(); say('API restarted; parameter waitlist.mode = ' + mongo("print(d.parameters.findOne({key:'waitlist.mode'}).value)"))
    scenario(api, 'FIFO', second_class, fillers, waiting)
    say('\nPASS waiting-list curl sequence (ALL_AT_ONCE and FIFO)')
except Exception as error:
    say('FAIL: ' + str(error))
    api_log = runtime / 'api.log'
    if api_log.exists():
        say('\n'.join(api_log.read_text().splitlines()[-30:]))
    sys.exit(1)
finally:
    if api is not None and api.process.poll() is None:
        api.stop()
    subprocess.run(['docker', 'rm', '-f', container], capture_output=True)
    subprocess.run(['rm', '-rf', str(runtime)], capture_output=True)
    say('Removed the disposable Mongo container and runtime directory')
