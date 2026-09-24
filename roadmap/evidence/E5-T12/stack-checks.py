"""E5-T12 verification helper (adapted from E5-T11's club-apply.py). On a disposable Mongo 7 replica set (local profile):
1. run `bin/core club:apply seeds/club-canic.yaml` twice (the second must make 0 changes and leave the Mongo documents
   unchanged), then start the API jar and read `GET /api/v1/levels` as the seeded admin: PENDENT must exist with
   `progression = false` (TER too), every other level `true`;
2. run `bin/core migration:playoff src/test/resources/fixtures/playoff --dry-run --club=canic` on the fictional fixtures;
3. anonymize those fixtures (`bin/core migration:anonymize`, one-use key) and dry-run the derivative: the counts must be
   the same, so the persones.csv joins survive the anonymizer.
Dry runs write nothing (checked with a Mongo snapshot). Generated credentials, keys and tokens stay private; logs are sanitized."""
import base64, json, os, re, secrets, shlex, socket, subprocess, tempfile, time
from pathlib import Path

root = Path.cwd(); output = root / 'roadmap/evidence/E5-T12'
runtime = Path(tempfile.mkdtemp(prefix='e5-t12-cli-')); container = 'e5-t12-cli-' + secrets.token_hex(4)
seed_password = secrets.token_urlsafe(28); master = base64.b64encode(secrets.token_bytes(32)).decode()
anonymize_key = secrets.token_urlsafe(40)
env = dict(os.environ, SPRING_PROFILES_ACTIVE='local', SEED_PASSWORD=seed_password, OIDC_MASTER_KEY=master,
           MONGODB_DATABASE='e5_t12', MONGODB_REPLICA_SET='rs0', SHARED_SCHEDULING_ENABLED='false', SERVER_PORT='0',
           MANAGEMENT_SERVER_PORT='0', ATTACHMENT_LOCAL_DIRECTORY=str(runtime / 'attachments'), EXPORT_LOCAL_DIRECTORY=str(runtime / 'exports'),
           MIGRATION_ANONYMIZE_KEY=anonymize_key)
HOST = 'app.agilitycanic.cat'
# The report summary lines ("members: created=…"), not the per-row lines ("members:83 …"); the full reports are in the step logs.
REPORT_SUMMARY = ('Playoff', 'members: ', 'dogs: ', 'familyGroups: ', 'accounts: ', 'Incidents')
tokens = []


def sanitize(value):
    for secret in [seed_password, master, anonymize_key] + tokens:
        value = value.replace(secret, '[truncated]')
    return re.sub(r'\b[0-9a-fA-F]{40,}\b', lambda m: m[0][:5] + '[truncated]', value)


def raw(cmd, **kwargs):
    return subprocess.run(cmd, text=True, capture_output=True, check=True, **kwargs).stdout


def step(number, name, cmd, show=('changes',)):
    proc = subprocess.run(cmd, text=True, capture_output=True, env=env, timeout=900)
    text = sanitize(proc.stdout + proc.stderr); file = f'{number:02d}-{name}.log'
    (output / file).write_text('$ ' + ' '.join(shlex.quote(a) for a in cmd) + f'        (exit {proc.returncode})\n' + text)
    print(f"$ {' '.join(shlex.quote(a) for a in cmd)} -> exit {proc.returncode} (log {file})", flush=True)
    print('\n'.join(line for line in text.splitlines() if any(s in line for s in show)), flush=True)
    if proc.returncode:
        raise RuntimeError(f'{name} failed; see {file}')
    return proc.stdout


def mongo(script):
    return raw(['docker', 'exec', container, 'mongosh', '--quiet', '--eval', "const d=db.getSiblingDB('e5_t12');" + script]).strip()


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


def summary(report):
    counts = {line.split(':')[0]: line for line in report.splitlines() if re.match(r'^(members|dogs|familyGroups|accounts): ', line)}
    incidents = next(line for line in report.splitlines() if line.startswith('Incidents: '))
    codes = dict((k, int(v)) for k, v in re.findall(r'(\w+)=(\d+)', incidents))
    return counts, codes


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
    print('Disposable Mongo 7 replica set on a random localhost port, database e5_t12 (empty), local profile; generated credentials private.', flush=True)
    step(3, 'club-apply-first', ['bin/core', 'club:apply', 'seeds/club-canic.yaml'])
    before = snapshot()
    second = step(4, 'club-apply-second', ['bin/core', 'club:apply', 'seeds/club-canic.yaml'])
    assert re.search(r'^0 changes', second, re.M), 'the second club:apply changed something'
    assert snapshot() == before, 'Mongo snapshot changed on the second club:apply'
    print('PASS second club:apply: 0 changes and complete Mongo document snapshot unchanged', flush=True)
    stored = mongo("d.levels.find().sort({order:1}).forEach(l => print(l.code + ' order=' + l.order + ' capacity=' + l.capacity + ' progression=' + l.progression));")
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
    for item in levels['items']:
        print(f"  {item['code']:8} order={item['order']} capacity={item['capacity']} color={item['color']} progression={str(item['progression']).lower()} name={json.dumps(item['name'], ensure_ascii=False)}", flush=True)
    (output / '05-get-levels.json').write_text(sanitize(json.dumps(levels, indent=2, ensure_ascii=False)) + '\n')
    assert dict(rows).get('PENDENT') is False and dict(rows).get('TER') is False, 'PENDENT and TER are outside the progression'
    assert all(progression is True for code, progression in rows if code not in ('TER', 'PENDENT')), 'every other level is in the progression'
    assert len(rows) == 10
    print('PASS GET /levels: PENDENT and TER progression=false, the other 8 levels true (full body 05-get-levels.json)', flush=True)
    api.terminate(); api.wait(30); api = None

    before = snapshot()
    report = step(6, 'migration-dry-run-fixtures', ['bin/core', 'migration:playoff', 'src/test/resources/fixtures/playoff', '--dry-run', '--club=canic'],
                  show=REPORT_SUMMARY)
    assert snapshot() == before, 'the dry run wrote to Mongo'
    counts, codes = summary(report)
    print(f"Dry run summary (fixtures): EMAIL_SHARED={codes.get('EMAIL_SHARED', 0)} PERSON_MERGED={codes.get('PERSON_MERGED', 0)} "
          f"LEVEL_PENDING={codes.get('LEVEL_PENDING', 0)} PLAN_UNMAPPED={codes.get('PLAN_UNMAPPED', 0)} MAPPING_INVALID={codes.get('MAPPING_INVALID', 0)}", flush=True)
    assert codes.get('EMAIL_SHARED') == 19 and codes.get('PERSON_MERGED') == 1, 'unexpected EMAIL_SHARED/PERSON_MERGED counts'
    assert 'errors=0' in counts['members'] and 'proposed=18' in counts['familyGroups']
    print('PASS dry run on the fixtures: no Mongo write, EMAIL_SHARED=19, PERSON_MERGED=1, familyGroups proposed=18', flush=True)

    anonymized = runtime / 'anonymized'
    step(7, 'migration-anonymize', ['bin/core', 'migration:anonymize', 'src/test/resources/fixtures/playoff', str(anonymized)], show=('Anonymized',))
    print('Anonymized files: ' + ', '.join(sorted(p.name for p in anonymized.iterdir())), flush=True)
    report2 = step(8, 'migration-dry-run-anonymized', ['bin/core', 'migration:playoff', str(anonymized), '--dry-run', '--club=canic'],
                   show=REPORT_SUMMARY)
    assert snapshot() == before, 'the dry run wrote to Mongo'
    counts2, codes2 = summary(report2)
    print(f"Dry run summary (anonymized): EMAIL_SHARED={codes2.get('EMAIL_SHARED', 0)} PERSON_MERGED={codes2.get('PERSON_MERGED', 0)}", flush=True)
    assert counts2 == counts and codes2 == codes, 'the anonymized derivative gives different counts'
    print('PASS dry run on the anonymized derivative: same entity counts and incident counts (the persones.csv join survived)', flush=True)
    print('HELPER_EXIT_0', flush=True)
finally:
    if api is not None:
        api.terminate()
        try: api.wait(30)
        except subprocess.TimeoutExpired: api.kill()
    subprocess.run(['docker', 'rm', '-f', container], capture_output=True)
