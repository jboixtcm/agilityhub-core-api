"""E5-T05 verification helper (local stack). On a disposable Mongo 7 replica set (local profile, random loopback port):
`bin/core club:apply seeds/club-canic.yaml` + `bin/core seed:demo --club=canic --seed=42`, then the API jar on that database
(scheduler and outbox on) and the S15 curl sequence with the seeded logins (admin@…, instructor@…):
  the admin switches the calendar of `risk-review` off (the switch stops the calendar, not the admin) → the test clock moves
  to 07:25 of the first day of the validated demo week (W+2) → GET /jobs → POST /jobs/risk-review/trigger {dryRun: true}
  (the plan) → {dryRun: false} (the summary) → GET /jobs/risk-review/runs/{runId} → GET /risk-review → the outbox events
  and `notifications` rows (N-17, N-08a, N-16) → an in-time cancellation that drops a class below the minimum → N-54.
Generated credentials and tokens stay private; ids are truncated in every log."""
import base64, collections, json, os, re, secrets, socket, subprocess, sys, tempfile, time, uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

root = Path.cwd()
runtime = Path(tempfile.mkdtemp(prefix='e5-t05-curl-')); container = 'e5-t05-curl-' + secrets.token_hex(4)
seed_password = secrets.token_urlsafe(28); master = base64.b64encode(secrets.token_bytes(32)).decode()
env = dict(os.environ, SPRING_PROFILES_ACTIVE='local', SEED_PASSWORD=seed_password, OIDC_MASTER_KEY=master,
           MONGODB_DATABASE='e5_jobs', MONGODB_REPLICA_SET='rs0', SHARED_SCHEDULING_ENABLED='false', SERVER_PORT='0',
           MANAGEMENT_SERVER_PORT='0', ATTACHMENT_LOCAL_DIRECTORY=str(runtime / 'attachments'), EXPORT_LOCAL_DIRECTORY=str(runtime / 'exports'))
MADRID = ZoneInfo('Europe/Madrid'); HOST = 'app.agilitycanic.cat'
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
    say(f"$ {' '.join(cmd)} -> exit {proc.returncode}")
    say('\n'.join('  ' + line for line in sanitize(proc.stdout + proc.stderr).splitlines()[-4:]))
    if proc.returncode:
        raise RuntimeError(f'{name} failed')
    return proc.stdout


def mongo(script):
    return raw(['docker', 'exec', container, 'mongosh', '--quiet', '--eval', "const d=db.getSiblingDB('e5_jobs');" + script]).strip()


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
        for _ in range(120):
            if mongo("print(d.domain_events.countDocuments({status:'PENDING'}))") == '0':
                break
            time.sleep(1)
        self.seen = ids('domain_events'); self.notes = ids('notifications')

    def call(self, method, path, expected, access=None, body=None, form=None, key=None, label=None):
        headers = ['Host: ' + HOST]
        if access: headers.append('Authorization: Bearer ' + access)
        if key: headers.append('Idempotency-Key: ' + key)
        args = ['curl', '-4', '--silent', '--show-error', '--max-time', '60', '--request', method, '--output', str(runtime / 'response'),
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
            say(f"curl {method} {path} -> {status}" + (f" {value.get('code')}" if isinstance(value, dict) and 'code' in value and status >= 400 else '')
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

    def effects(self, types=None, codes=None, samples=2):
        """The outbox events and notification rows produced since the last call (grouped, with a few samples per code)."""
        time.sleep(6)
        new_events = sorted(ids('domain_events') - self.seen); new_notes = sorted(ids('notifications') - self.notes)
        self.seen |= set(new_events); self.notes |= set(new_notes)
        events = json.loads(mongo(f"print(EJSON.stringify(d.domain_events.find({{_id:{{$in:{json.dumps(new_events)}}}}}).sort({{occurredAt:1,_id:1}}).toArray()))")) if new_events else []
        counts = collections.Counter(e['type'] for e in events)
        say('  outbox events: ' + (', '.join(f'{t} ×{n}' for t, n in sorted(counts.items())) or 'none'))
        for event in events:
            if types and event['type'] in types:
                say(f"  outbox {event['type']} {event['status']} {event['aggregateId']} {json.dumps(event.get('payload'), ensure_ascii=False)}")
        notes = json.loads(mongo(f"print(EJSON.stringify(d.notifications.find({{_id:{{$in:{json.dumps(new_notes)}}}}}).sort({{code:1,channel:1,accountId:1}}).toArray()))")) if new_notes else []
        grouped = collections.Counter((n['code'], n['channel'], n['status']) for n in notes)
        say('  notifications: ' + (', '.join(f'{c} {ch} {st} ×{n}' for (c, ch, st), n in sorted(grouped.items())) or 'none'))
        shown = collections.Counter()
        for n in notes:
            if (codes is None or n['code'] in codes) and shown[n['code']] < samples:
                shown[n['code']] += 1
                say(f"  notification {n['code']} {n['channel']} {n['status']} account {n.get('accountId') or '-'} "
                    + json.dumps(n.get('variables') or {}, ensure_ascii=False) + (f" sms({len(n['body'])}): {n['body']}" if n.get('body') else ''))
        return events, notes


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
    say('Disposable Mongo 7 replica set on a random localhost port, database e5_jobs, local profile; generated credentials private.')
    step('club-apply', ['bin/core', 'club:apply', 'seeds/club-canic.yaml'])
    step('seed-demo', ['bin/core', 'seed:demo', '--club=canic', '--seed=42'])
    api = Api(); say('API jar up on a random loopback port (local profile, scheduler and outbox on); the seed outbox is drained')
    admin = api.token('admin@example.test')

    say('\n0. The admin switches the calendar of risk-review off (manual runs still work, R-15-09)')
    say('  ' + json.dumps(api.call('PUT', '/api/v1/jobs/risk-review/switch', 200, access=admin, body={'enabled': False}, label='admin')))
    # The first day of the validated demo week (the seed's W+2) with ACTIVE classes.
    first = mongo("const c=d.class_sessions.find({state:'ACTIVE'}).sort({startsAt:1}).limit(1).toArray()[0]; print(c.date)")
    day = datetime.fromisoformat(first).replace(hour=7, minute=25, tzinfo=MADRID)
    api.clock(day)
    admin = api.token('admin@example.test')
    api.drain()

    say('\n1. GET /jobs')
    jobs = api.call('GET', '/api/v1/jobs', 200, access=admin, label='admin')
    for row in jobs['items']:
        say('  ' + json.dumps({k: row.get(k) for k in ('name', 'jobName', 'module', 'enabled', 'schedule', 'nextScheduledForLocal')}, ensure_ascii=False)
            + (f" lastRun {row['lastRun']['status']}/{row['lastRun']['trigger']}" if row.get('lastRun') else ''))

    say('\n2. POST /jobs/risk-review/trigger {dryRun: true} -> the plan')
    dry = api.call('POST', '/api/v1/jobs/risk-review/trigger', 200, access=admin, body={'dryRun': True}, label='admin')
    say('  ' + json.dumps({k: dry[k] for k in ('runId', 'job', 'scheduledForLocal', 'trigger', 'dryRun', 'status')}))
    say('  counters ' + json.dumps(dry['effects']['counters']))
    for item in dry['effects']['items']:
        say(f"  plan {item['action']} {item['entityType']} {item['entityId']} {json.dumps(item.get('detail'))}")
    api.effects()

    say('\n3. POST /jobs/risk-review/trigger {dryRun: false} -> the summary')
    real = api.call('POST', '/api/v1/jobs/risk-review/trigger', 200, access=admin, body={'dryRun': False}, label='admin')
    say('  ' + json.dumps({k: real[k] for k in ('runId', 'job', 'scheduledForLocal', 'trigger', 'dryRun', 'status', 'durationMs')}))
    say('  counters ' + json.dumps(real['effects']['counters']))
    if [i['entityId'] for i in real['effects']['items']] != [i['entityId'] for i in dry['effects']['items']]:
        raise AssertionError('plan and effects differ')
    say('  plan ≡ effects: the same ' + str(len(real['effects']['items'])) + ' items, actions without WOULD_')
    api.effects(types={'ClassAutoCancelled', 'ClassAtRisk', 'ClassCancelledByClub', 'SchedulerRun'}, codes={'N-17', 'N-08a', 'N-16'})

    say('\n4. GET /jobs/risk-review/runs/{runId}')
    sheet = api.call('GET', f"/api/v1/jobs/risk-review/runs/{real['runId']}", 200, access=admin, label='admin')
    say('  ' + json.dumps({k: sheet[k] for k in ('runId', 'status', 'errors', 'parametersSnapshot')}, ensure_ascii=False))
    say('  items ' + json.dumps([f"{i['action']} {i['entityId']}" for i in sheet['effects']['items']]))
    listing = api.call('GET', '/api/v1/jobs/risk-review/runs?filter=dryRun:eq:false', 200, access=admin, label='admin, history')
    say('  ' + json.dumps([{k: r[k] for k in ('runId', 'trigger', 'dryRun', 'status', 'skipReason', 'counters')} for r in listing['items']]))

    say('\n5. GET /risk-review')
    review = api.call('GET', '/api/v1/risk-review', 200, access=admin, label='admin')
    say('  ' + json.dumps({k: review[k] for k in ('date', 'reviewTime', 'lookaheadDays', 'minDogs', 'autoCancelSameDay')}))
    for item in review['items']:
        say('  ' + json.dumps({k: item.get(k) for k in ('dayLabel', 'startTime', 'displayDescription', 'ringName', 'bookedCount', 'status', 'notified')}, ensure_ascii=False))
    api.call('GET', '/api/v1/risk-review', 403, access=api.token('member@example.test'), label='member')

    say('\n6. An in-time cancellation that drops a class below the minimum (R-15-12b)')
    target = json.loads(mongo("const now=new Date(Date.now()); const c=d.class_sessions.find({state:'ACTIVE','counters.booked':2,'risk.exempt':false,"
                              "startsAt:{$gt:new Date(" + str(int((day + timedelta(hours=6)).timestamp() * 1000)) + ")}}).sort({startsAt:1}).limit(1).toArray()[0];"
                              "print(EJSON.stringify({id:c._id,date:c.date,time:c.startTime,booking:d.bookings.find({classSessionId:c._id,state:'ACTIVE'}).limit(1).toArray()[0]._id}))"))
    say(f"  class {target['id']} {target['date']} {target['time']} with 2 registrants; the instructor records a notice for one of them")
    api.call('POST', f"/api/v1/bookings/{target['booking']}/cancellation", 200, access=api.token('instructor@example.test'), body={}, label='instructor, «ha avisat»')
    api.effects(types={'ClassBelowMinimum', 'BookingCancelled'}, codes={'N-54'}, samples=4)
    state = mongo(f"const c=d.class_sessions.findOne({{_id:'{target['id']}'}}); print(c.state+' booked='+c.counters.booked+' lowAlertSentAt='+(c.risk.lowAlertSentAt?'set':'null'))")
    say('  class after the alert: ' + state)

    say('\n7. The same processes from the CLI (step 10), on the same database, without the scheduler')
    step('jobs-run-dry', ['bin/core', 'jobs:run', 'risk-review', '--club=canic', '--dry-run'])
    step('jobs-run-cleanup', ['bin/core', 'jobs:run', 'cleanup', '--club=canic'])
    unknown = subprocess.run(['bin/core', 'jobs:run', 'no-such-job', '--club=canic'], text=True, capture_output=True, env=env, timeout=900)
    say(f"$ bin/core jobs:run no-such-job --club=canic -> exit {unknown.returncode} ({'JOB_UNKNOWN' if 'JOB_UNKNOWN' in unknown.stdout + unknown.stderr else 'no JOB_UNKNOWN in the output'})")
    if unknown.returncode == 0:
        raise AssertionError('an unknown route id must fail')
    say('\nPASS risk-review curl sequence')
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
