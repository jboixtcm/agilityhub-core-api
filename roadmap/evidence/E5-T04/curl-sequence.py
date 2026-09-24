"""E5-T04 verification helper (local stack). On a disposable Mongo 7 replica set (local profile, random loopback port):
`bin/core club:apply seeds/club-canic.yaml` + `bin/core seed:demo --club=canic --seed=42`, then the API jar on that database
and the S09 free-training curl sequence with the seeded logins (member@…, member.2@… … member.10@…, instructor@…, admin@…):
  summary → slots for the window → book without ringId («Qualsevol») → the cell turns BOOKED/OWN_TRAINING → a second dog on
  the same ring and slot → 409 SLOT_TAKEN {freeRings} → the instructor blocks the ring over the booking → 422
  RING_HAS_BOOKINGS → the admin repeats it with cancelBookings: true → booking CANCELLED_BY_CLUB + N-47 rows → a cancellation
  past the 120 min threshold → 422 TRAINING_CANCEL_TOO_LATE (catalog rule 0) → GET /day-grid in both views for the day.
After every step it prints the outbox events and `notifications` rows the step produced. Generated credentials and tokens
stay private; ids are truncated in every log."""
import base64, json, os, re, secrets, socket, subprocess, sys, tempfile, time, uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

root = Path.cwd()
runtime = Path(tempfile.mkdtemp(prefix='e5-t04-curl-')); container = 'e5-t04-curl-' + secrets.token_hex(4)
seed_password = secrets.token_urlsafe(28); master = base64.b64encode(secrets.token_bytes(32)).decode()
env = dict(os.environ, SPRING_PROFILES_ACTIVE='local', SEED_PASSWORD=seed_password, OIDC_MASTER_KEY=master,
           MONGODB_DATABASE='e5_training', MONGODB_REPLICA_SET='rs0', SHARED_SCHEDULING_ENABLED='false', SERVER_PORT='0',
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
    return raw(['docker', 'exec', container, 'mongosh', '--quiet', '--eval', "const d=db.getSiblingDB('e5_training');" + script]).strip()


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
        time.sleep(4)  # the outbox dispatcher runs every second; notifications come from its consumers
        new_events = sorted(ids('domain_events') - self.seen); new_notes = sorted(ids('notifications') - self.notes)
        lines = []
        if new_events:
            lines += mongo(f"d.domain_events.find({{_id:{{$in:{json.dumps(new_events)}}}}}).sort({{occurredAt:1,_id:1}}).forEach(e=>print('  outbox '+e.type+' '+e.status+' '+e.aggregateId+' '+EJSON.stringify(e.payload)));").splitlines()
        if new_notes:
            lines += mongo(f"d.notifications.find({{_id:{{$in:{json.dumps(new_notes)}}}}}).sort({{code:1,channel:1,accountId:1}}).forEach(n=>print('  notification '+n.code+' '+n.channel+' '+n.status+' account '+(n.accountId||'-')+(n.variables?' '+EJSON.stringify(n.variables):'')+(n.body?' sms('+n.body.length+'): '+n.body:'')));").splitlines()
        self.seen |= set(new_events); self.notes |= set(new_notes)
        say('\n'.join(line if line.startswith('  ') else '  ' + line for line in lines) or '  (no outbox event or notification)')


def eligible_logins(api):
    """Member logins with at least one eligible dog (R-09-01), from their own training summary."""
    result = []
    for email in LOGINS:
        summary = api.call('GET', '/api/v1/me/training-summary', 200, access=api.token(email))
        own = [d for d in summary['eligibleDogs'] if d['ownerName'] is None]
        if own:
            result.append({'email': email, 'dog': own[0]['id'], 'dogName': own[0]['name'], 'default': summary['defaultDogId']})
    return result


def free_slot(grid, rings, after=None):
    """The first bookable slot where every reservable ring is FREE (seeded classes and blocks make others busy)."""
    for day in grid['days']:
        for slot in day['slots']:
            if slot['bookable'] and (after is None or slot['startsAt'] > after) and all(slot['rings'][r]['state'] == 'FREE' for r in rings):
                return day['date'], slot
    raise RuntimeError('no slot with every reservable ring free')


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
    say('Disposable Mongo 7 replica set on a random localhost port, database e5_training, local profile; generated credentials private.')
    step('club-apply', ['bin/core', 'club:apply', 'seeds/club-canic.yaml'])
    step('seed-demo', ['bin/core', 'seed:demo', '--club=canic', '--seed=42'])
    api = Api(); say('API jar up on a random loopback port (local profile); the seed outbox is drained')
    now = datetime.now(timezone.utc).replace(second=0, microsecond=0)
    tomorrow = (now.astimezone(MADRID) + timedelta(days=1)).replace(hour=6, minute=0)
    api.clock(tomorrow.astimezone(timezone.utc) - timedelta(days=1))  # a stable morning, one day before the rehearsal day
    logins = eligible_logins(api)
    say(f"{len(logins)} member logins with an eligible own dog: " + ', '.join(f"{l['email'].split('@')[0]} ({l['dogName']})" for l in logins))
    if len(logins) < 2:
        raise RuntimeError('need two logins with an eligible dog')
    first, second = logins[0], logins[1]
    member = api.token(first['email'])

    say('\n1. GET /me/training-summary')
    summary = api.call('GET', '/api/v1/me/training-summary', 200, access=member, label=first['email'].split('@')[0])
    say('  ' + json.dumps({k: summary[k] for k in ('defaultDogId', 'limitUnit', 'weekOpensAt', 'counter')}))
    say('  eligibleDogs ' + json.dumps([{k: d[k] for k in ('name', 'levelName', 'ownerName', 'rightSource')} for d in summary['eligibleDogs']], ensure_ascii=False))

    say('\n2. GET /training-slots for the booking window')
    today = datetime.fromisoformat(iso(tomorrow - timedelta(days=1)).replace('Z', '+00:00')).astimezone(MADRID).date()
    grid = api.call('GET', f"/api/v1/training-slots?from={today}&to={today + timedelta(days=10)}&dogId={first['dog']}", 200, access=member, label='member')
    rings = [r['id'] for r in grid['rings']]
    say(f"  window {grid['window']} · rings (catalog order) {[r['name'] for r in grid['rings']]} · days {[d['date'] for d in grid['days']]} · slots per open day "
        + str([len(d['slots']) for d in grid['days']]))
    date, slot = free_slot(grid, rings, after=iso(tomorrow))
    say(f"  chosen slot {date} {slot['startsAtLocal']}–{slot['endsAtLocal']} ({slot['startsAt']}): every reservable ring FREE")

    say('\n3. POST /training-bookings without ringId («Qualsevol»)')
    booked = api.call('POST', '/api/v1/training-bookings', 201, access=member, body={'dogId': first['dog'], 'startsAt': slot['startsAt']}, key=str(uuid.uuid4()), label='book')
    say('  ' + json.dumps({k: booked[k] for k in ('id', 'ringName', 'slotId', 'startsAtLocal', 'endsAtLocal', 'state', 'origin', 'cancellableUntil', 'counter')}, ensure_ascii=False))
    api.effects()

    say('\n4. the slot turns BOOKED/OWN_TRAINING')
    grid = api.call('GET', f"/api/v1/training-slots?from={date}&to={date}&dogId={first['dog']}", 200, access=member, label='member')
    cell = next(s for d in grid['days'] for s in d['slots'] if s['startsAt'] == slot['startsAt'])['rings'][booked['ringId']]
    say('  cell ' + json.dumps(cell))
    if cell['state'] != 'BOOKED' or cell['reason'] != 'OWN_TRAINING':
        raise AssertionError('the booked cell is not OWN_TRAINING')

    say('\n5. a second dog on the same ring and slot')
    taken = api.call('POST', '/api/v1/training-bookings', 409, access=api.token(second['email']),
                     body={'dogId': second['dog'], 'startsAt': slot['startsAt'], 'ringId': booked['ringId']}, key=str(uuid.uuid4()), label=second['email'].split('@')[0])
    say('  details ' + json.dumps(taken['details']))

    say('\n6. the instructor blocks the ring over the booking')
    start = datetime.fromisoformat(slot['startsAt'].replace('Z', '+00:00'))
    block = {'ringId': booked['ringId'], 'from': iso(start), 'to': iso(start + timedelta(minutes=60)), 'kind': 'BLOCK', 'reason': 'MAINTENANCE', 'note': 'Reg de la pista (fictici)'}
    refused = api.call('POST', '/api/v1/ring-blocks', 422, access=api.token('instructor@example.test'), body=block, key=str(uuid.uuid4()), label='instructor')
    say('  details ' + json.dumps(refused['details'], ensure_ascii=False))

    say('\n7. the admin repeats it with cancelBookings: true')
    created = api.call('POST', '/api/v1/ring-blocks', 201, access=api.token('admin@example.test'), body=dict(block, cancelBookings=True), key=str(uuid.uuid4()), label='admin')
    say(f"  block {created['id']} {created['fromLocal']}–{created['toLocal']} {created['kind']}/{created['reason']}")
    detail = api.call('GET', f"/api/v1/training-bookings/{booked['id']}", 200, access=member, label='member reads the booking')
    say('  booking ' + json.dumps({k: detail[k] for k in ('state', 'cancelledBy', 'cancelReason')}))
    api.effects()

    say('\n8. a cancellation past the threshold')
    grid = api.call('GET', f"/api/v1/training-slots?from={date}&to={date}&dogId={first['dog']}", 200, access=member)
    _, later = free_slot(grid, rings, after=iso(start + timedelta(minutes=90)))
    late = api.call('POST', '/api/v1/training-bookings', 201, access=member, body={'dogId': first['dog'], 'startsAt': later['startsAt']}, key=str(uuid.uuid4()), label='book another slot')
    say(f"  booking {late['id']} {late['startsAtLocal']} {late['ringName']} cancellableUntil {late['cancellableUntil']}")
    api.effects()
    api.clock(datetime.fromisoformat(later['startsAt'].replace('Z', '+00:00')) - timedelta(minutes=119))
    too_late = api.call('POST', f"/api/v1/training-bookings/{late['id']}/cancellation", 422, access=api.token(first['email']), body={}, label='member, 119 min before')
    say('  details ' + json.dumps(too_late['details']))

    say(f'\n9. GET /day-grid for {date}, both views')
    member_grid = api.call('GET', f'/api/v1/day-grid?date={date}&view=member', 200, access=api.token(first['email']), label='view=member')
    staff_grid = api.call('GET', f'/api/v1/day-grid?date={date}&view=instructor', 200, access=api.token('instructor@example.test'), label='view=instructor')
    for name, value in (('member', member_grid), ('instructor', staff_grid)):
        cells = [dict(time=row['time'], **{k: c.get(k) for k in ('ringId', 'kind', 'reason', 'who', 'note', 'trainingBookingIds', 'blockId', 'endTime') if k in c})
                 for row in value['rows'] for c in row['cells'] if c.get('kind') in ('TRAINING', 'BLOCK', 'OCCUPIED')]
        say(f'  view={name}: ' + json.dumps(cells, ensure_ascii=False))
    leaked = [c for row in member_grid['rows'] for c in row['cells'] if any(k in c for k in ('who', 'note', 'trainingBookingIds', 'blockId'))]
    if leaked:
        raise AssertionError('view=member leaks staff fields')
    say('\nPASS free-training curl sequence')
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
