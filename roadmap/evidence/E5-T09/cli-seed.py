"""E5-T09 verification helper (from E4-T05's): on a disposable Mongo 7 replica set (local profile) run
`bin/core club:apply seeds/club-canic.yaml`, `bin/core seed:demo --club=canic --seed=42` twice (the second must change
nothing), then `seed:demo --reanchor` twice. The CLI runs on the real clock, so the re-anchor passes `--week-start` = the
club-local Monday two weeks after the run's own (what the run date would be two weeks later). Generated credentials stay
private; every log is written sanitized."""
import base64, datetime, os, re, secrets, shlex, subprocess, sys, tempfile, time, zoneinfo
from pathlib import Path

root = Path.cwd(); output = root / 'roadmap/evidence/E5-T09'
runtime = Path(tempfile.mkdtemp(prefix='e5-t09-cli-')); container = 'e5-t09-cli-' + secrets.token_hex(4)
seed_password = secrets.token_urlsafe(28); master = base64.b64encode(secrets.token_bytes(32)).decode()
env = dict(os.environ, SPRING_PROFILES_ACTIVE='local', SEED_PASSWORD=seed_password, OIDC_MASTER_KEY=master,
           MONGODB_DATABASE='e5_cli', MONGODB_REPLICA_SET='rs0', SHARED_SCHEDULING_ENABLED='false', SERVER_PORT='0',
           MANAGEMENT_SERVER_PORT='0', ATTACHMENT_LOCAL_DIRECTORY=str(runtime / 'attachments'), EXPORT_LOCAL_DIRECTORY=str(runtime / 'exports'))
today = datetime.datetime.now(zoneinfo.ZoneInfo('Europe/Madrid')).date()
monday = today - datetime.timedelta(days=today.weekday())
anchor = monday + datetime.timedelta(weeks=2)


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
    return mongo("d.weeks.find().sort({startDate:1}).toArray().forEach(w => { const ids = d.class_sessions.find({weekId: w._id}).toArray().map(c => c._id);"
                 " print(String(w.startDate) + ' ' + w.state + ' ' + ids.length + ' classes, ' + d.bookings.countDocuments({classSessionId: {$in: ids}}) + ' bookings'); });")


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
          f'run Monday {monday}, re-anchor week start {anchor}; generated credentials private.', flush=True)
    step(5, 'club-apply', ['bin/core', 'club:apply', 'seeds/club-canic.yaml'])
    first = step(6, 'seed-demo-first', ['bin/core', 'seed:demo', '--club=canic', '--seed=42'])
    assert 'changes (demo planning, week start ' + str(monday) in first
    before = snapshot()
    second = step(7, 'seed-demo-second', ['bin/core', 'seed:demo', '--club=canic', '--seed=42'])
    assert '0 changes (demo seed)' in second and '0 changes (demo planning' in second, 'second run changed data'
    assert snapshot() == before, 'Mongo snapshot changed on the second run'
    print('PASS second seed:demo run: 0 changes and complete Mongo document snapshot unchanged', flush=True)
    print('Weeks after the first run:\n' + weeks(), flush=True)
    reanchor = step(8, 'seed-demo-reanchor', ['bin/core', 'seed:demo', '--club=canic', '--seed=42', '--reanchor', '--week-start=' + str(anchor)])
    assert '0 changes (demo seed)' in reanchor and 'changes (demo planning, week start ' + str(anchor) in reanchor
    assert '\n0 changes (demo planning' not in '\n' + reanchor, 're-anchor changed nothing'
    print('Weeks after the re-anchor:\n' + weeks(), flush=True)
    after = snapshot()
    again = step(9, 'seed-demo-reanchor-again', ['bin/core', 'seed:demo', '--club=canic', '--seed=42', '--reanchor', '--week-start=' + str(anchor)])
    assert '0 changes (demo planning, week start ' + str(anchor) in again, 'second re-anchor changed data'
    assert snapshot() == after, 'Mongo snapshot changed on the second re-anchor'
    print('PASS re-anchor: new weeks generated once; the second re-anchor on the same week reports 0 changes and the snapshot is unchanged', flush=True)
except Exception as error:
    print('FAIL: ' + sanitize(str(error)), file=sys.stderr); sys.exit(1)
finally:
    subprocess.run(['docker', 'rm', '-f', container], capture_output=True)
    subprocess.run(['rm', '-rf', str(runtime)], capture_output=True)
    print('Removed the disposable Mongo container and runtime directory', flush=True)
