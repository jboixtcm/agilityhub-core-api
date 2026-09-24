"""E5-T11 verification helper (from E5-T09's cli-seed.py and E5-T04's curl-sequence.py): on a disposable Mongo 7 replica
set (local profile) run `bin/core club:apply seeds/club-canic.yaml` twice (the second must make 0 changes and leave the
Mongo documents unchanged), then start the API jar on it and read `GET /api/v1/levels` as the seeded admin: Teràpia
(`TER`) must have `progression = false` and every other level `true`. Generated credentials and tokens stay private;
every log is written sanitized."""
import base64, json, os, re, secrets, shlex, socket, subprocess, tempfile, time
from pathlib import Path

root = Path.cwd(); output = root / 'roadmap/evidence/E5-T11'
runtime = Path(tempfile.mkdtemp(prefix='e5-t11-cli-')); container = 'e5-t11-cli-' + secrets.token_hex(4)
seed_password = secrets.token_urlsafe(28); master = base64.b64encode(secrets.token_bytes(32)).decode()
env = dict(os.environ, SPRING_PROFILES_ACTIVE='local', SEED_PASSWORD=seed_password, OIDC_MASTER_KEY=master,
           MONGODB_DATABASE='e5_levels', MONGODB_REPLICA_SET='rs0', SHARED_SCHEDULING_ENABLED='false', SERVER_PORT='0',
           MANAGEMENT_SERVER_PORT='0', ATTACHMENT_LOCAL_DIRECTORY=str(runtime / 'attachments'), EXPORT_LOCAL_DIRECTORY=str(runtime / 'exports'))
HOST = 'app.agilitycanic.cat'
tokens = []


def sanitize(value):
    for secret in [seed_password, master] + tokens:
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
    return raw(['docker', 'exec', container, 'mongosh', '--quiet', '--eval', "const d=db.getSiblingDB('e5_levels');" + script]).strip()


def snapshot():
    return mongo("print(EJSON.stringify(d.getCollectionNames().sort().map(n=>[n,d.getCollection(n).find().sort({_id:1}).toArray()])));")


def curl(method, path, expected, access=None, form=None, base=None):
    headers = ['Host: ' + HOST] + (['Authorization: Bearer ' + access] if access else [])
    args = ['curl', '-4', '--silent', '--show-error', '--max-time', '30', '--request', method, '--output', str(runtime / 'response'),
            '--write-out', '%{http_code}', '--config', '-']
    if form is not None:
        payload = runtime / 'request'; payload.write_text('&'.join(f'{k}={v}' for k, v in form.items()))
        headers.append('Content-Type: application/x-www-form-urlencoded'); args += ['--data-binary', '@' + str(payload)]
    config = '\n'.join('header = ' + json.dumps(h) for h in headers) + '\nurl = ' + json.dumps(base + path)
    result = subprocess.run(args, input=config, text=True, capture_output=True)
    status = int(result.stdout or 0); text = (runtime / 'response').read_text() if (runtime / 'response').exists() else ''
    if status != expected:
        raise AssertionError(sanitize(f'{method} {path}: expected {expected}, got {status} {text[:300]}'))
    return json.loads(text)


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
    print('Disposable Mongo 7 replica set on a random localhost port, database e5_levels (empty), local profile; generated credentials private.', flush=True)
    step(3, 'club-apply-first', ['bin/core', 'club:apply', 'seeds/club-canic.yaml'])
    before = snapshot()
    second = step(4, 'club-apply-second', ['bin/core', 'club:apply', 'seeds/club-canic.yaml'])
    assert re.search(r'^0 changes', second, re.M), 'the second club:apply changed something'
    assert snapshot() == before, 'Mongo snapshot changed on the second club:apply'
    print('PASS second club:apply: 0 changes and complete Mongo document snapshot unchanged', flush=True)
    stored = mongo("d.levels.find().sort({order:1}).forEach(l => print(l.code + ' progression=' + l.progression));")
    print('Stored levels (mongosh):\n' + '\n'.join('  ' + line for line in stored.splitlines()), flush=True)

    port = socket.socket(); port.bind(('127.0.0.1', 0)); api_port = port.getsockname()[1]; port.close()
    api_env = dict(env, SERVER_PORT=str(api_port), MAIL_LOCAL_DIRECTORY=str(runtime / 'mailbox'), AUTH_ISSUER='https://id.example.test',
                   OIDC_LOGIN_URL='https://id.example.test/login', LOGGING_LEVEL_ROOT='ERROR')
    jar = next(root.glob('target/agilityhub-core-api-*.jar')); log = open(runtime / 'api.log', 'a')
    api = subprocess.Popen(['java', '-jar', str(jar)], env=api_env, stdout=log, stderr=subprocess.STDOUT)
    base = f'http://127.0.0.1:{api_port}'
    for _ in range(180):
        if subprocess.run(['curl', '-4', '-fsS', base + '/api/v1/health'], capture_output=True).returncode == 0:
            break
        time.sleep(1)
    else:
        raise RuntimeError('API did not start')
    access = curl('POST', '/oauth2/token', 200, form=dict(grant_type='password', client_id='clubs-app', username='admin@example.test',
                                                          password=seed_password), base=base)['access_token']
    tokens.append(access)
    levels = curl('GET', '/api/v1/levels', 200, access=access, base=base)
    rows = [(item['code'], item['progression']) for item in levels['items']]
    print('curl GET /api/v1/levels (Host app.agilitycanic.cat, seeded ADMIN) -> 200', flush=True)
    for code, progression in rows:
        print(f'  {code:4} progression={str(progression).lower()}', flush=True)
    (output / '05-get-levels.json').write_text(sanitize(json.dumps(levels, indent=2, ensure_ascii=False)) + '\n')
    assert dict(rows).get('TER') is False, 'Teràpia must be outside the progression'
    assert all(progression is True for code, progression in rows if code != 'TER'), 'every other level is in the progression'
    assert len(rows) == 9
    print('PASS GET /levels: TER progression=false, the other 8 levels true (full body 05-get-levels.json)', flush=True)
    print('HELPER_EXIT_0', flush=True)
finally:
    if api is not None:
        api.terminate()
        try: api.wait(30)
        except subprocess.TimeoutExpired: api.kill()
    subprocess.run(['docker', 'rm', '-f', container], capture_output=True)
