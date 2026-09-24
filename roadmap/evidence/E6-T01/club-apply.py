"""E6-T01 verification helper (from E5-T11's club-apply.py): on a disposable Mongo 7 replica set (local profile, empty
database) run `bin/core club:apply seeds/club-canic.yaml` twice. The second run must make 0 changes and leave the Mongo
documents unchanged; the S10 collections must carry their indexes after both runs, identical (index creation is
idempotent). Runtime files live under target/; generated credentials stay private; every log is written sanitized."""
import base64, os, re, secrets, shlex, subprocess, sys, time
from pathlib import Path

root = Path.cwd(); output = root / 'roadmap/evidence/E6-T01'
# Optional first log number (round 1: 7 → 07/08; round 2: 15 → 15/16), so a rerun never overwrites earlier evidence.
first_log = int(sys.argv[1]) if len(sys.argv) > 1 else 7
runtime = root / 'target' / ('e6-t01-cli-' + secrets.token_hex(4)); runtime.mkdir(parents=True)
container = 'e6-t01-cli-' + secrets.token_hex(4)
seed_password = secrets.token_urlsafe(28); master = base64.b64encode(secrets.token_bytes(32)).decode()
env = dict(os.environ, SPRING_PROFILES_ACTIVE='local', SEED_PASSWORD=seed_password, OIDC_MASTER_KEY=master,
           MONGODB_DATABASE='e6_contract', MONGODB_REPLICA_SET='rs0', SHARED_SCHEDULING_ENABLED='false', SERVER_PORT='0',
           MANAGEMENT_SERVER_PORT='0', ATTACHMENT_LOCAL_DIRECTORY=str(runtime / 'attachments'), EXPORT_LOCAL_DIRECTORY=str(runtime / 'exports'))
COLLECTIONS = ['attendances', 'tasks', 'followup_items', 'followup_read_marks', 'attachments']
EXPECTED = {'attendances': ['attendance_club_booking', 'attendance_club_class', 'attendance_club_dog_starts', 'attendance_club_state_notice_date'],
            'tasks': ['task_club_dog_deleted_state_created'], 'followup_items': ['followup_club_activity', 'followup_club_kind_activity'],
            'followup_read_marks': ['followup_read_club_account'], 'attachments': ['attachment_club_entity_removed']}


def sanitize(value):
    for secret in [seed_password, master]:
        value = value.replace(secret, '[truncated]')
    return re.sub(r'\b[0-9a-fA-F]{40,}\b', lambda m: m[0][:5] + '[truncated]', value)


def raw(cmd, **kwargs):
    return subprocess.run(cmd, text=True, capture_output=True, check=True, **kwargs).stdout


def step(number, name, cmd):
    proc = subprocess.run(cmd, text=True, capture_output=True, env=env, timeout=900)
    text = sanitize(proc.stdout + proc.stderr); file = f'{number:02d}-{name}.log'
    (output / file).write_text('$ ' + ' '.join(shlex.quote(a) for a in cmd) + f'        (exit {proc.returncode})\n' + text)
    print(f"$ {' '.join(shlex.quote(a) for a in cmd)} -> exit {proc.returncode} (log {file})", flush=True)
    print('\n'.join(line for line in text.splitlines() if 'changes' in line), flush=True)
    if proc.returncode:
        raise RuntimeError(f'{name} failed; see {file}')
    return proc.stdout


def mongo(script):
    return raw(['docker', 'exec', container, 'mongosh', '--quiet', '--eval', "const d=db.getSiblingDB('e6_contract');" + script]).strip()


def snapshot():
    return mongo("print(EJSON.stringify(d.getCollectionNames().sort().map(n=>[n,d.getCollection(n).find().sort({_id:1}).toArray()])));")


def indexes():
    lines = {}
    for name in COLLECTIONS:
        lines[name] = mongo(f"d.getCollection('{name}').getIndexes().forEach(i => print(i.name + ' ' + EJSON.stringify(i.key) + (i.unique ? ' unique' : '')));").splitlines()
    return lines


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
    print('Disposable Mongo 7 replica set on a random localhost port, database e6_contract (empty), local profile; generated credentials private.', flush=True)
    step(first_log, 'club-apply-first', ['bin/core', 'club:apply', 'seeds/club-canic.yaml'])
    first = indexes(); before = snapshot()
    second = step(first_log + 1, 'club-apply-second', ['bin/core', 'club:apply', 'seeds/club-canic.yaml'])
    assert re.search(r'^0 changes', second, re.M), 'the second club:apply changed something'
    assert snapshot() == before, 'Mongo snapshot changed on the second club:apply'
    print('PASS second club:apply: 0 changes and complete Mongo document snapshot unchanged', flush=True)
    again = indexes()
    for name in COLLECTIONS:
        print(f'Indexes of {name} after the second run:', flush=True)
        for line in again[name]:
            print('  ' + line, flush=True)
        names = [line.split(' ')[0] for line in again[name]]
        assert all(expected in names for expected in EXPECTED[name]), f'{name}: missing S10 index'
    assert first == again, 'indexes changed between the two runs'
    print('PASS the S10 indexes exist after both runs and are identical (idempotent creation)', flush=True)
    print('HELPER_EXIT_0', flush=True)
finally:
    subprocess.run(['docker', 'rm', '-f', container], capture_output=True)
