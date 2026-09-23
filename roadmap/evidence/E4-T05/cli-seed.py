"""E4-T05 verification helper: `bin/core club:apply` then `bin/core seed:demo --club=canic --seed=42` twice on a
disposable Mongo 7 replica set (local profile). Generated credentials stay private; every log is written sanitized."""
import base64, os, re, secrets, shlex, subprocess, sys, tempfile, time
from pathlib import Path

root = Path.cwd(); output = root / 'roadmap/evidence/E4-T05'
runtime = Path(tempfile.mkdtemp(prefix='e4-t05-cli-')); container = 'e4-t05-cli-' + secrets.token_hex(4)
seed_password = secrets.token_urlsafe(28); master = base64.b64encode(secrets.token_bytes(32)).decode()
env = dict(os.environ, SPRING_PROFILES_ACTIVE='local', SEED_PASSWORD=seed_password, OIDC_MASTER_KEY=master,
           MONGODB_DATABASE='e4_cli', MONGODB_REPLICA_SET='rs0', SHARED_SCHEDULING_ENABLED='false', SERVER_PORT='0',
           MANAGEMENT_SERVER_PORT='0', ATTACHMENT_LOCAL_DIRECTORY=str(runtime / 'attachments'), EXPORT_LOCAL_DIRECTORY=str(runtime / 'exports'))


def sanitize(value):
    for secret in (seed_password, master):
        value = value.replace(secret, '[truncated]')
    value = re.sub(r'([?&]signature=)[A-Za-z0-9_-]+', r'\1[truncated]', value)
    return re.sub(r'\b[0-9a-fA-F]{40,}\b', lambda m: m[0][:5] + '[truncated]', value)


def raw(cmd, **kwargs):
    return subprocess.run(cmd, text=True, capture_output=True, check=True, **kwargs).stdout


def step(number, name, cmd):
    proc = subprocess.run(cmd, text=True, capture_output=True, env=env, timeout=600)
    text = sanitize(proc.stdout + proc.stderr); file = f'{number:02d}-{name}.log'
    (output / file).write_text('$ ' + ' '.join(shlex.quote(a) for a in cmd) + f'        (exit {proc.returncode})\n' + text)
    print(f"$ {' '.join(shlex.quote(a) for a in cmd)} -> exit {proc.returncode} (log {file})", flush=True)
    print('\n'.join(text.splitlines()[-40:]), flush=True)
    if proc.returncode:
        raise RuntimeError(f'{name} failed; see {file}')
    return proc.stdout


def snapshot():
    script = ("const d=db.getSiblingDB('e4_cli'); print(EJSON.stringify(d.getCollectionNames().sort()"
              ".map(n=>[n,d.getCollection(n).find().sort({_id:1}).toArray()])));")
    return raw(['docker', 'exec', container, 'mongosh', '--quiet', '--eval', script]).strip()


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
    print('Disposable Mongo 7 replica set on a random localhost port, database e4_cli, local profile; generated credentials private.', flush=True)
    step(8, 'club-apply', ['bin/core', 'club:apply', 'seeds/club-canic.yaml'])
    first = step(9, 'seed-demo-first', ['bin/core', 'seed:demo', '--club=canic', '--seed=42'])
    assert 'changes (demo planning' in first
    before = snapshot()
    second = step(10, 'seed-demo-second', ['bin/core', 'seed:demo', '--club=canic', '--seed=42'])
    assert '0 changes (demo seed)' in second and '0 changes (demo planning' in second, 'second run changed data'
    assert snapshot() == before, 'Mongo snapshot changed on the second run'
    print('PASS second seed:demo run: 0 changes and complete Mongo document snapshot unchanged', flush=True)
except Exception as error:
    print('FAIL: ' + sanitize(str(error)), file=sys.stderr); sys.exit(1)
finally:
    subprocess.run(['docker', 'rm', '-f', container], capture_output=True)
    subprocess.run(['rm', '-rf', str(runtime)], capture_output=True)
    print('Removed the disposable Mongo container and runtime directory', flush=True)
