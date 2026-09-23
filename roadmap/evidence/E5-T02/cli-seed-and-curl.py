"""E5-T02 verification helper. On a disposable Mongo 7 replica set (local profile, random loopback port):
1. `bin/core club:apply seeds/club-canic.yaml`, then `bin/core seed:demo --club=canic --seed=42` twice, comparing the full
   Mongo document snapshot before and after the second run (must be 0 changes);
2. starts the API jar on that database and runs the S08 curl sequence with the seeded member account:
   hold → confirm → GET → cancel in time → hold at the weekly limit → swap → cancel late, printing the outbox events and
   `notifications` rows each step produced. The W+2 classes only open for booking a week before (R-08-01), so the
   sequence moves the local-profile test clock (`POST /api/v1/test/clock`) into that week first.
Generated credentials and tokens stay private; ids are truncated in every log."""
import base64, json, os, re, secrets, shlex, socket, subprocess, sys, tempfile, time, uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

root = Path.cwd(); output = root / 'roadmap/evidence/E5-T02'
runtime = Path(tempfile.mkdtemp(prefix='e5-t02-cli-')); container = 'e5-t02-cli-' + secrets.token_hex(4)
seed_password = secrets.token_urlsafe(28); master = base64.b64encode(secrets.token_bytes(32)).decode()
env = dict(os.environ, SPRING_PROFILES_ACTIVE='local', SEED_PASSWORD=seed_password, OIDC_MASTER_KEY=master,
           MONGODB_DATABASE='e5_cli', MONGODB_REPLICA_SET='rs0', SHARED_SCHEDULING_ENABLED='false', SERVER_PORT='0',
           MANAGEMENT_SERVER_PORT='0', ATTACHMENT_LOCAL_DIRECTORY=str(runtime / 'attachments'), EXPORT_LOCAL_DIRECTORY=str(runtime / 'exports'))
MADRID = ZoneInfo('Europe/Madrid'); HOST = 'app.agilitycanic.cat'
tokens = []


def sanitize(value):
    for secret in [seed_password, master] + tokens:
        value = value.replace(secret, '[truncated]')
    value = re.sub(r'(token=)[A-Za-z0-9._-]+', r'\1[truncated]', value)
    value = re.sub(r'\b([0-9a-f]{8})-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b', r'\1…', value)
    return re.sub(r'\b[0-9a-fA-F]{40,}\b', lambda m: m[0][:5] + '[truncated]', value)


def raw(cmd, **kwargs):
    return subprocess.run(cmd, text=True, capture_output=True, check=True, **kwargs).stdout


def step(number, name, cmd):
    proc = subprocess.run(cmd, text=True, capture_output=True, env=env, timeout=900)
    text = sanitize(proc.stdout + proc.stderr); file = f'{number:02d}-{name}.log'
    (output / file).write_text('$ ' + ' '.join(shlex.quote(a) for a in cmd) + f'        (exit {proc.returncode})\n' + text)
    print(f"$ {' '.join(shlex.quote(a) for a in cmd)} -> exit {proc.returncode} (log {file})", flush=True)
    print('\n'.join(text.splitlines()[-12:]), flush=True)
    if proc.returncode:
        raise RuntimeError(f'{name} failed; see {file}')
    return proc.stdout


def mongo(script):
    return raw(['docker', 'exec', container, 'mongosh', '--quiet', '--eval', "const d=db.getSiblingDB('e5_cli');" + script]).strip()


def snapshot():
    return mongo("print(EJSON.stringify(d.getCollectionNames().sort().map(n=>[n,d.getCollection(n).find().sort({_id:1}).toArray()])));")


def ids(collection):
    return set(json.loads(mongo(f"print(EJSON.stringify(d.{collection}.find({{}},{{_id:1}}).toArray().map(x=>x._id)))")))


class Api:
    def __init__(self, port):
        self.base = f'http://127.0.0.1:{port}'; self.seen = ids('domain_events'); self.notes = ids('notifications')

    def drain(self):
        """Let the outbox deliver the seed's own events first, so each step shows only what it produced."""
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
            print(sanitize(f"curl {method} {path.split('?')[0]} -> {status}" + (f" {value.get('code')}" if isinstance(value, dict) and 'code' in value and status >= 400 else '')), flush=True)
        if status != expected:
            raise AssertionError(sanitize(f'{method} {path}: expected {expected}, got {status} {text[:300]}'))
        return value

    def effects(self):
        time.sleep(3)  # the outbox dispatcher runs every second
        new_events = sorted(ids('domain_events') - self.seen); new_notes = sorted(ids('notifications') - self.notes)
        lines = []
        if new_events:
            lines += mongo(f"d.domain_events.find({{_id:{{$in:{json.dumps(new_events)}}}}}).sort({{occurredAt:1,_id:1}}).forEach(e=>print('  outbox '+e.type+' '+e.status+' '+e.aggregateId+' '+EJSON.stringify(e.payload)));").splitlines()
        if new_notes:
            lines += mongo(f"d.notifications.find({{_id:{{$in:{json.dumps(new_notes)}}}}}).sort({{code:1,channel:1}}).forEach(n=>print('  notification '+n.code+' '+n.channel+' '+n.status+' account '+(n.accountId||'-')));").splitlines()
        self.seen |= set(new_events); self.notes |= set(new_notes)
        print(sanitize('\n'.join(line if line.startswith('  ') else '  ' + line for line in lines) or '  (no outbox event or notification)'), flush=True)


def curl_sequence():
    port = socket.socket(); port.bind(('127.0.0.1', 0)); api_port = port.getsockname()[1]; port.close()
    api_env = dict(env, SERVER_PORT=str(api_port), SHARED_SCHEDULING_ENABLED='true', MAIL_LOCAL_DIRECTORY=str(runtime / 'mailbox'),
                   AUTH_ISSUER='https://id.example.test', OIDC_LOGIN_URL='https://id.example.test/login', LOGGING_LEVEL_ROOT='ERROR')
    jar = next(root.glob('target/agilityhub-core-api-*.jar'))
    log = open(runtime / 'api.log', 'w')
    process = subprocess.Popen(['java', '-jar', str(jar)], env=api_env, stdout=log, stderr=subprocess.STDOUT)
    try:
        api = None
        for _ in range(180):
            try:
                if subprocess.run(['curl', '-4', '-fsS', f'http://127.0.0.1:{api_port}/api/v1/health'], capture_output=True).returncode == 0:
                    api = Api(api_port); break
            except Exception:
                pass
            time.sleep(1)
        if api is None:
            raise RuntimeError('API did not start')
        api.drain()
        print(f'API jar up on a random loopback port (local profile, database e5_cli); the seed outbox is drained', flush=True)
        # The seeded member account (never registered by the seed) and its bookable data, read from Mongo.
        facts = json.loads(mongo("""
const acc=d.accounts.findOne({email:'member@example.test'}); const m=d.members.findOne({accountId:acc._id});
const dogs=d.dogs.find({memberId:m._id,status:'ACTIVE'}).toArray();
const out=dogs.map(g=>({dog:g._id,name:g.name,classes:d.class_sessions.find({state:'ACTIVE',$or:[{levelIds:g.levelId},{levelIds:{$size:0}}]}).sort({startsAt:1}).toArray()
  .filter(c=>c.counters.booked<c.capacity).map(c=>({id:c._id,startsAt:c.startsAt.toISOString(),date:c.date,time:c.startTime}))}));
print(EJSON.stringify({member:m._id,dogs:out}));"""))
        dog = max(facts['dogs'], key=lambda g: len(g['classes']))
        classes = dog['classes']
        first = datetime.fromisoformat(classes[0]['startsAt'].replace('Z', '+00:00'))
        week = [c for c in classes if datetime.fromisoformat(c['startsAt'].replace('Z', '+00:00')) < first + timedelta(days=6)]
        if len(week) < 4:
            raise RuntimeError('fewer than four bookable W+2 classes for the member dog')
        a, b, c, e = week[0], week[1], week[2], week[3]
        print(f"member dog {dog['name']}: classes {a['date']} {a['time']}, {b['date']} {b['time']}, {c['date']} {c['time']}, {e['date']} {e['time']}", flush=True)
        # Test clock: 6 h before the first class, so the whole W+2 week is W0 (limit 2 per dog, R-08-01/03).
        at = (first - timedelta(hours=6)).astimezone(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')
        api.call('POST', '/api/v1/test/clock', 200, body={'instant': at}, label='clock')
        print(f'  test clock -> {at} (club-local {datetime.fromisoformat(at.replace("Z", "+00:00")).astimezone(MADRID):%a %d-%m %H:%M})', flush=True)
        token = api.call('POST', '/oauth2/token', 200, form=dict(grant_type='password', client_id='clubs-app', username='member@example.test', password=seed_password))['access_token']
        tokens.append(token)

        def hold(cls):
            return api.call('POST', '/api/v1/seat-holds', 201, access=token, body={'classSessionId': cls['id'], 'dogId': dog['dog']}, label='hold')

        def confirm(held, swap=None):
            body = {'seatHoldId': held['id']} | ({'swapBookingId': swap} if swap else {})
            return api.call('POST', '/api/v1/bookings', 201, access=token, body=body, key=str(uuid.uuid4()), label='confirm')

        print('\n1. hold → confirm (class A)', flush=True)
        held = hold(a); print(f"  hold expiresAt={held['expiresAt']} serverNow={held['serverNow']} limit={held['limit']['count']}/{held['limit']['max']} reached={held['limit']['reached']}")
        booking = confirm(held); print(f"  booking {booking['state']} origin={booking['origin']} calendarLinks.ics={'calendar.ics?token=' in booking['calendarLinks']['ics']}")
        api.effects()
        print('\n2. GET /bookings/{id}', flush=True)
        detail = api.call('GET', f"/api/v1/bookings/{booking['id']}", 200, access=token, label='detail')
        print(f"  displayState={detail['displayState']} instructorName={detail['classSession']['instructorName']} instructorVisibleAt={detail['classSession']['instructorVisibleAt']}")
        api.effects()
        print('\n3. cancel in time (6 h before, threshold 240 min)', flush=True)
        cancelled = api.call('POST', f"/api/v1/bookings/{booking['id']}/cancellation", 200, access=token, body={}, label='cancel')
        print(f"  state={cancelled['state']} late={cancelled['cancellation']['late']} minutesBefore={cancelled['cancellation']['minutesBefore']}")
        api.effects()
        print('\n4. book B and C, then hold D at the weekly limit', flush=True)
        bb = confirm(hold(b)); cc = confirm(hold(c)); api.effects()
        at_limit = hold(e); print(f"  hold limit={at_limit['limit']['count']}/{at_limit['limit']['max']} reached={at_limit['limit']['reached']} swappable={len(at_limit['limit']['swappable'])}")
        api.effects()
        print('\n5. swap: confirm D cancelling B in the same transaction', flush=True)
        swapped = confirm(at_limit, swap=bb['id'])
        print(f"  new booking swapFromBookingId={swapped['swapFromBookingId'][:8]}…")
        api.effects()
        print('\n6. cancel C late (test clock 3 h before C)', flush=True)
        late_at = (datetime.fromisoformat(c['startsAt'].replace('Z', '+00:00')) - timedelta(hours=3)).astimezone(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')
        api.call('POST', '/api/v1/test/clock', 200, body={'instant': late_at}, label='clock')
        token = api.call('POST', '/oauth2/token', 200, form=dict(grant_type='password', client_id='clubs-app', username='member@example.test', password=seed_password))['access_token']
        tokens.append(token)
        late = api.call('POST', f"/api/v1/bookings/{cc['id']}/cancellation", 200, access=token, body={}, label='cancel')
        print(f"  state={late['state']} late={late['cancellation']['late']} minutesBefore={late['cancellation']['minutesBefore']}")
        api.effects()
        print('PASS curl sequence', flush=True)
    finally:
        process.terminate()
        try: process.wait(30)
        except subprocess.TimeoutExpired: process.kill()
        log.close()


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
    print('Disposable Mongo 7 replica set on a random localhost port, database e5_cli, local profile; generated credentials private.', flush=True)
    step(4, 'club-apply', ['bin/core', 'club:apply', 'seeds/club-canic.yaml'])
    first = step(5, 'seed-demo-first', ['bin/core', 'seed:demo', '--club=canic', '--seed=42'])
    assert 'changes (demo planning' in first
    print(mongo("print('bookings='+d.bookings.countDocuments({})+' (with pack '+d.bookings.countDocuments({packMovementId:{$ne:null}})+', origin APP '+d.bookings.countDocuments({origin:'APP'})+')'"
                "+' waitlist_entries='+d.waitlist_entries.countDocuments({})+' demo_class_bookings='+d.demo_class_bookings.countDocuments({})"
                "+' BookingCreated='+d.domain_events.countDocuments({type:'BookingCreated'})+' seat_holds='+d.seat_holds.countDocuments({}))"), flush=True)
    before = snapshot()
    second = step(6, 'seed-demo-second', ['bin/core', 'seed:demo', '--club=canic', '--seed=42'])
    assert '0 changes (demo seed)' in second and '0 changes (demo planning' in second, 'second run changed data'
    assert snapshot() == before, 'Mongo snapshot changed on the second run'
    print('PASS second seed:demo run: 0 changes and complete Mongo document snapshot unchanged', flush=True)
    curl_sequence()
except Exception as error:
    print('FAIL: ' + sanitize(str(error)), file=sys.stderr)
    api_log = runtime / 'api.log'
    if api_log.exists():
        print(sanitize('\n'.join(api_log.read_text().splitlines()[-30:])), file=sys.stderr)
    sys.exit(1)
finally:
    subprocess.run(['docker', 'rm', '-f', container], capture_output=True)
    subprocess.run(['rm', '-rf', str(runtime)], capture_output=True)
    print('Removed the disposable Mongo container and runtime directory', flush=True)
