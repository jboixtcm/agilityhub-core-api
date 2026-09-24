"""E5-T14 verification helper (from E5-T09's cli-seed.py): on a disposable Mongo 7 replica set (local profile) run
`bin/core club:apply seeds/club-canic.yaml`, the first `bin/core seed:demo --club=canic --seed=42`, then
`seed:demo --reanchor` one week after the first run, twice. The CLI runs on the real clock, so the re-anchor passes
`--week-start` = the club-local Monday one week after the run's own (what the run date one week later anchors to).
Checks: the new current week (the old draft W+1) is VALIDATED with ACTIVE (bookable) classes and no DRAFT left, the kept
weeks get no new registrants, the generated week gets them, and the second re-anchor changes nothing. Generated
credentials stay private; every log is written sanitized."""
import base64, datetime, os, re, secrets, shlex, subprocess, sys, tempfile, time, zoneinfo
from pathlib import Path

root = Path.cwd(); output = root / 'roadmap/evidence/E5-T14'
runtime = Path(tempfile.mkdtemp(prefix='e5-t14-cli-')); container = 'e5-t14-cli-' + secrets.token_hex(4)
seed_password = secrets.token_urlsafe(28); master = base64.b64encode(secrets.token_bytes(32)).decode()
env = dict(os.environ, SPRING_PROFILES_ACTIVE='local', SEED_PASSWORD=seed_password, OIDC_MASTER_KEY=master,
           MONGODB_DATABASE='e5_cli', MONGODB_REPLICA_SET='rs0', SHARED_SCHEDULING_ENABLED='false', SERVER_PORT='0',
           MANAGEMENT_SERVER_PORT='0', ATTACHMENT_LOCAL_DIRECTORY=str(runtime / 'attachments'), EXPORT_LOCAL_DIRECTORY=str(runtime / 'exports'))
today = datetime.datetime.now(zoneinfo.ZoneInfo('Europe/Madrid')).date()
monday = today - datetime.timedelta(days=today.weekday())
anchor = monday + datetime.timedelta(weeks=1)


def sanitize(value):
    for secret in (seed_password, master):
        value = value.replace(secret, '[truncated]')
    value = re.sub(r'([?&]signature=)[A-Za-z0-9_-]+', r'\1[truncated]', value)
    return re.sub(r'\b[0-9a-fA-F]{40,}\b', lambda m: m[0][:5] + '[truncated]', value)


def raw(cmd, **kwargs):
    return subprocess.run(cmd, text=True, capture_output=True, check=True, **kwargs).stdout


def step(number, name, cmd):
    proc = subprocess.run(cmd, text=True, capture_output=True, env=env, timeout=900)
    text = sanitize(proc.stdout + proc.stderr); file = f'{number:02d}-{name}.log'
    (output / file).write_text('$ ' + ' '.join(shlex.quote(a) for a in cmd) + f'        (exit {proc.returncode})\n' + text)
    print(f"$ {' '.join(shlex.quote(a) for a in cmd)} -> exit {proc.returncode} (log {file})", flush=True)
    print('\n'.join(line for line in text.splitlines() if 'changes' in line or line.startswith('{')), flush=True)
    if proc.returncode:
        raise RuntimeError(f'{name} failed; see {file}')
    return proc.stdout


def mongo(script):
    return raw(['docker', 'exec', container, 'mongosh', '--quiet', '--eval', "const d=db.getSiblingDB('e5_cli');" + script]).strip()


def snapshot():
    return mongo("print(EJSON.stringify(d.getCollectionNames().sort().map(n=>[n,d.getCollection(n).find().sort({_id:1}).toArray()])));")


def weeks():
    return mongo("d.weeks.find().sort({startDate:1}).toArray().forEach(w => { const cs = d.class_sessions.find({weekId: w._id}).toArray();"
                 " const by = s => cs.filter(c => c.state === s).length; const ids = cs.map(c => c._id);"
                 " print(String(w.startDate) + ' ' + w.state + ' ' + cs.length + ' classes (ACTIVE ' + by('ACTIVE') + ', DRAFT ' + by('DRAFT')"
                 " + ', CANCELLED ' + by('CANCELLED') + '), ' + d.bookings.countDocuments({classSessionId: {$in: ids}}) + ' bookings'); });")


def week(start):
    line = next((l for l in weeks().splitlines() if l.startswith(str(start) + ' ')), None)
    if line is None:
        raise RuntimeError(f'week {start} missing')
    m = re.match(r'\S+ (\w+) (\d+) classes \(ACTIVE (\d+), DRAFT (\d+), CANCELLED (\d+)\), (\d+) bookings', line)
    return {'state': m[1], 'classes': int(m[2]), 'active': int(m[3]), 'draft': int(m[4]), 'bookings': int(m[6])}


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
    print(f'Disposable Mongo 7 replica set on a random localhost port, database e5_cli, local profile; run date {today}, '
          f'run Monday {monday}, re-anchor week start {anchor} (one week later); generated credentials private.', flush=True)
    step(5, 'club-apply', ['bin/core', 'club:apply', 'seeds/club-canic.yaml'])
    first = step(6, 'seed-demo-first', ['bin/core', 'seed:demo', '--club=canic', '--seed=42'])
    assert 'changes (demo planning, week start ' + str(monday) in first
    print('Weeks after the first run:\n' + weeks(), flush=True)
    before_current, before_next = week(anchor), week(anchor + datetime.timedelta(weeks=1))
    assert before_current['state'] == 'GENERATED' and before_current['draft'] > 0, before_current
    reanchor = step(7, 'seed-demo-reanchor-one-week', ['bin/core', 'seed:demo', '--club=canic', '--seed=42', '--reanchor', '--week-start=' + str(anchor)])
    assert 'validatedKeptWeeks=1' in reanchor, 'the kept draft week was not validated'
    print('Weeks after the re-anchor:\n' + weeks(), flush=True)
    current, kept_next, generated = week(anchor), week(anchor + datetime.timedelta(weeks=1)), week(anchor + datetime.timedelta(weeks=2))
    assert current['state'] == 'VALIDATED' and current['draft'] == 0 and current['active'] > 0, current
    assert current['bookings'] == before_current['bookings'] and kept_next['bookings'] == before_next['bookings'], 'a kept week got registrants'
    assert generated['state'] == 'VALIDATED' and generated['bookings'] > 0, generated
    print(f"PASS current week {anchor}: VALIDATED, {current['active']} ACTIVE (bookable) classes, 0 DRAFT; kept weeks' bookings unchanged "
          f"({current['bookings']} and {kept_next['bookings']}); generated week {anchor + datetime.timedelta(weeks=2)}: {generated['bookings']} bookings", flush=True)
    after = snapshot()
    again = step(8, 'seed-demo-reanchor-again', ['bin/core', 'seed:demo', '--club=canic', '--seed=42', '--reanchor', '--week-start=' + str(anchor)])
    assert '0 changes (demo planning, week start ' + str(anchor) in again, 'second re-anchor changed data'
    assert snapshot() == after, 'Mongo snapshot changed on the second re-anchor'
    print('PASS second re-anchor on the same week: 0 changes and complete Mongo document snapshot unchanged', flush=True)
except Exception as error:
    print('FAIL: ' + sanitize(str(error)), file=sys.stderr); sys.exit(1)
finally:
    subprocess.run(['docker', 'rm', '-f', container], capture_output=True)
    subprocess.run(['rm', '-rf', str(runtime)], capture_output=True)
    print('Removed the disposable Mongo container and runtime directory', flush=True)
