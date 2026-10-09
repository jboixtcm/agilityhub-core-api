#!/usr/bin/env python3
"""E11-T04 proof with fictional data, random project and cleanup on every outcome."""
import argparse
import base64
import http.cookies
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import signal
import socket
import subprocess
import sys
import tempfile
import time
import urllib.parse

ROOT = Path(__file__).resolve().parent.parent


def run(command, *, env=None, check=True, **kwargs):
    result = subprocess.run(command, env=env, capture_output=True, **kwargs)
    if check and result.returncode:
        # Inputs/outputs can contain generated secrets. Display only the operation.
        raise RuntimeError(f'{command[0]} {command[1] if len(command) > 1 else ""} exited {result.returncode}')
    return result


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--image-tag', default='main')
    parser.add_argument('--evidence-prefix', type=Path, help='New filename prefix for exact child-command outputs; never overwritten')
    parser.add_argument('--skip-helper-builds', action='store_true', help='Use cached helpers after checking their code and pinned runtime')
    parser.add_argument('--security-only', action='store_true', help='E11-T03: headers/CORS/logs; skip backup helpers')
    args = parser.parse_args()
    os.umask(0o077)
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))
    if args.evidence_prefix:
        args.evidence_prefix.parent.mkdir(parents=True, exist_ok=True)
        if list(args.evidence_prefix.parent.glob(args.evidence_prefix.name + '-*')):
            raise RuntimeError('Evidence prefix already exists')
    evidence_index = 0

    def record(command, result):
        nonlocal evidence_index
        evidence_index += 1
        if args.evidence_prefix:
            stem = args.evidence_prefix.parent / f'{args.evidence_prefix.name}-{evidence_index:02d}'
            stem.with_suffix('.command').write_text(shlex.join(command) + '\n')
            stem.with_suffix('.exit').write_text(str(result.returncode) + '\n')
            stdout = result.stdout or ''
            stderr = result.stderr or ''
            if isinstance(stdout, bytes):
                stdout = stdout.decode()
                stderr = stderr.decode()
            stem.with_suffix('.log').write_text(stdout + stderr)

    with tempfile.TemporaryDirectory(prefix='e11-t04-') as directory:
        work = Path(directory)
        envfile = work / 'local.env'
        writer_envfile, recovery_envfile = work / 'writer.env', work / 'recovery.env'
        writer_user, reader_user = 'backup-writer', 'backup-reader'
        writer_password, reader_password = secrets.token_hex(24), secrets.token_hex(24)
        values = {}
        for line in (ROOT / 'deploy/.env.prod.example').read_text().splitlines():
            if line and not line.startswith('#'):
                key, value = line.split('=', 1)
                values[key] = value
        values.update(IMAGE_TAG=args.image_tag, LOCAL_HTTP_PORT=str(free_port()), LOCAL_HTTPS_PORT=str(free_port()))
        for key in ('MONGO_ROOT_PASSWORD', 'MONGODB_PASSWORD', 'MONGO_BACKUP_PASSWORD', 'SEED_PASSWORD',
                    'BACKUP_S3_ACCESS_KEY', 'BACKUP_S3_SECRET_KEY', 'OIDC_LEARN_CLIENT_SECRET'):
            values[key] = secrets.token_hex(24)
        for key in ('OIDC_MASTER_KEY', 'SIGNUP_CAPABILITY_KEY', 'BOOKING_CALENDAR_KEY', 'EMAIL_UNSUBSCRIBE_KEY',
                    'BILLING_BANK_KEY', 'BILLING_SECRETS_KEY'):
            values[key] = base64.b64encode(secrets.token_bytes(32)).decode()
        # Valid ephemeral P-256 keys exercise strict production provider construction.
        private = run(['openssl', 'ecparam', '-name', 'prime256v1', '-genkey', '-noout']).stdout
        der = run(['openssl', 'ec', '-outform', 'DER'], input=private).stdout
        public = run(['openssl', 'ec', '-pubout', '-outform', 'DER'], input=private).stdout
        assert der[5:7] == b'\x04\x20' and public[-65] == 4
        values.update(VAPID_PRIVATE_KEY=base64.urlsafe_b64encode(der[7:39]).decode().rstrip('='),
                      VAPID_PUBLIC_KEY=base64.urlsafe_b64encode(public[-65:]).decode().rstrip('='),
                      VAPID_SUBJECT='mailto:operator@example.test',
                      SENDGRID_WEBHOOK_PUBLIC_KEY=base64.b64encode(public).decode(),
                      SENDGRID_API_KEY='fictional-' + secrets.token_hex(24),
                      TWILIO_ACCOUNT_SID='AC' + secrets.token_hex(16), TWILIO_AUTH_TOKEN=secrets.token_hex(16))
        values['MONGO_REPLICA_KEY'] = base64.b64encode(secrets.token_bytes(512)).decode()
        values.update(BACKUP_S3_BUCKET='fictional-backups', BACKUP_S3_PREFIX='mongo/', BACKUP_S3_ENDPOINT='http://minio:9000',
                      LOCAL_MONGODB_URI='mongodb://coreApp:' + values['MONGODB_PASSWORD'] + '@mongo:27017/agilityhub?authSource=admin&replicaSet=rs0&directConnection=true')
        subnet = secrets.randbelow(200) + 20
        values['DEPLOY_SUBNET'] = f'172.29.{subnet}.0/24'
        values['CADDY_IPV4_ADDRESS'] = f'172.29.{subnet}.10'
        values['TRUSTED_PROXY_PATTERN'] = "'" + re.escape(values['CADDY_IPV4_ADDRESS']) + "'"
        project = 'e11-t04-' + secrets.token_hex(4)
        environment = dict(os.environ, DEPLOY_ENV_FILE=str(envfile), DEPLOY_LOCAL='1', COMPOSE_PROJECT_NAME=project)
        environment['BUILDX_CONFIG'] = str(work / 'buildx')
        # Never inherit credentials from the calling shell into the fictional stack.
        for key in values:
            environment.pop(key, None)

        def write_env():
            envfile.write_text(''.join(f'{key}={value}\n' for key, value in values.items()))
            for file, user, password in ((writer_envfile, writer_user, writer_password),
                                         (recovery_envfile, reader_user, reader_password)):
                scoped = dict(values, BACKUP_S3_ACCESS_KEY=user, BACKUP_S3_SECRET_KEY=password)
                file.write_text(''.join(f'{key}={value}\n' for key, value in scoped.items()))
                file.chmod(0o600)

        write_env()
        compose = ['docker', 'compose', '--env-file', str(envfile), '-p', project,
                   '-f', str(ROOT / 'deploy/compose.prod.yaml'), '-f', str(ROOT / 'deploy/compose.prod.local.yaml')]

        if args.security_only:
            override = work / 'security.yaml'
            override.write_text('services:\n  core:\n    environment:\n      SPRING_PROFILES_ACTIVE: prod,structured-logs\n      LOGGING_STRUCTURED_FORMAT_CONSOLE: com.agilityhub.core.configuration.PrivacyLogFormatter\n')
            compose += ['-f', str(override)]

        def docker(*arguments, **kwargs):
            return run(compose + list(arguments), env=environment, **kwargs)

        def script(name, *arguments, expected=0, wrong_writer=False, failure=None):
            selected = writer_envfile if name == 'backup-mongo' or wrong_writer else recovery_envfile
            invocation_env = dict(environment, DEPLOY_ENV_FILE=str(selected))
            result = run([str(ROOT / 'bin' / name), *arguments], env=invocation_env, check=False, text=True)
            print(f'COMMAND DEPLOY_ENV_FILE={selected} bin/{name} {" ".join(arguments)} -> exit {result.returncode}', flush=True)
            record([str(ROOT / 'bin' / name), *arguments], result)
            print(result.stdout.strip(), flush=True)
            print(result.stderr.strip(), flush=True)
            if result.returncode != expected:
                raise RuntimeError(f'{name}: expected exit {expected}')
            if failure is not None and failure not in result.stderr:
                raise RuntimeError(f'{name}: expected the failure {failure!r}')
            return result.stdout

        def helper(code):
            return docker('run', '--rm', '--no-deps', '-T', '--entrypoint', '/opt/backup/bin/python',
                          'backup', '-c', code, text=True).stdout

        try:
            resolved = json.loads(docker('config', '--format', 'json', text=True).stdout)
            assert resolved['services']['core']['environment'].get('BILLING_BANK_KEY') == values['BILLING_BANK_KEY'], \
                'BILLING_BANK_KEY must reach Core from the deployment environment'
            assert resolved['services']['core']['environment'].get('BILLING_SECRETS_KEY') == values['BILLING_SECRETS_KEY'], \
                'BILLING_SECRETS_KEY must reach Core from the deployment environment'
            assert 'SPRING_DATA_MONGODB_URI' not in resolved['services']['core']['environment']
            for key in ('MONGODB_HOST', 'MONGODB_USERNAME', 'MONGODB_PASSWORD', 'MONGODB_DATABASE',
                        'MONGODB_AUTH_DATABASE', 'MONGODB_REPLICA_SET'):
                expected = {'MONGODB_HOST': 'mongo', 'MONGODB_AUTH_DATABASE': 'admin', 'MONGODB_REPLICA_SET': 'rs0'}.get(key, values.get(key))
                assert resolved['services']['core']['environment'][key] == expected
            assert resolved['services']['core']['environment']['SPRING_PROFILES_ACTIVE'].split(',')[0] == 'prod'
            assert resolved['services']['seed']['environment']['SPRING_PROFILES_ACTIVE'] == 'local'
            assert not resolved['services']['core']['environment'].get('SMS_ALLOWED_NUMBERS')
            assert resolved['networks']['default']['internal']
            assert set(resolved['services']['core']['networks']) == {'default'}
            assert 'ingress' in resolved['services']['caddy']['networks']
            print('PASS prod Core, local seed, both billing keys, private network with no provider egress', flush=True)
            mongo_image = resolved['services']['mongo']['image']
            if run(['docker', 'image', 'inspect', mongo_image], check=False).returncode:
                # Explicit first-use preparation; compose up never refreshes Mongo.
                run(['docker', 'pull', mongo_image])
            image_info = json.loads(run(['docker', 'image', 'inspect', resolved['services']['core']['image']], text=True).stdout)[0]
            print('IMAGE revision=' + (image_info['Config'].get('Labels') or {}).get('org.opencontainers.image.revision', 'unlabelled')[:12]
                  + ' id=' + image_info['Id'][:19] + '[truncated]', flush=True)
            if not args.security_only:
                if not args.skip_helper_builds:
                    print('COMMAND ' + shlex.join(compose + ['build', 'backup', 'minio']), flush=True)
                    build = docker('build', 'backup', 'minio', check=False, text=True)
                    print('BUILD_EXIT ' + str(build.returncode), flush=True)
                    if build.returncode:
                        print(build.stderr[-2500:], flush=True)
                        raise RuntimeError('Backup image build failed')
                installed = docker('run', '--rm', '--no-deps', '-T', '--entrypoint', 'cat',
                                   'backup', '/opt/backup/backup.py').stdout
                assert installed == (ROOT / 'deploy/backup/backup.py').read_bytes(), 'Cached helper source is stale'
                version = docker('run', '--rm', '--no-deps', '-T', '--entrypoint', 'mongod', 'backup', '--version', text=True).stdout
                assert 'v7.0.41' in version
                print('PASS helper contains exact current source and pinned Mongo 7.0.41', flush=True)
                generated = docker('run', '--rm', '--no-deps', '-T', '--entrypoint', 'age-keygen', 'backup', text=True)
                identity = next(line for line in generated.stdout.splitlines() if line.startswith('AGE-SECRET-KEY-'))
                environment['BACKUP_AGE_IDENTITY'] = identity
                values['BACKUP_AGE_RECIPIENT'] = docker('run', '--rm', '--no-deps', '-T', '--entrypoint', 'age-keygen', 'backup', '-y', input=(identity + '\n').encode()).stdout.decode().strip()
                write_env()
            print('COMMAND ' + shlex.join(compose + ['up', '-d', '--wait', '--wait-timeout', '420']), flush=True)
            targets = ['caddy'] if args.security_only else []
            started = docker('up', '-d', '--wait', '--wait-timeout', '420', *targets, check=False, text=True)
            record(compose + ['up', '-d', '--wait', '--wait-timeout', '420', *targets], started)
            print(started.stdout + started.stderr, flush=True)
            print('STARTUP_EXIT ' + str(started.returncode), flush=True)
            if started.returncode:
                # Store detailed private logs outside the repo for diagnosis only.
                # Report environment-safe error lines, never dump an app log.
                for service in ('mongo', 'seed', 'core', 'caddy', 'minio'):
                    logs = docker('logs', '--no-color', '--tail', '100', service, check=False, text=True).stdout
                    failures = [line for line in logs.splitlines() if any(word in line for word in ('ERROR', 'Exception', 'Error:', 'invalid', 'failed'))]
                    if service == 'seed' and not failures:
                        failures = logs.splitlines()[-12:]
                    for line in failures[-5:]:
                        for secret in values.values():
                            if len(secret) > 12:
                                line = line.replace(secret, '[redacted]')
                        line = re.sub(r'[\w.+-]+@[\w.-]+', '[email redacted]', line)
                        print(f'{service}: {line[:350]}', flush=True)
                raise RuntimeError('Local stack failed readiness')
            print('PASS authenticated production topology became healthy with local override', flush=True)
            print(f'RUNNER_CONTEXT DEPLOY_ENV_FILE={envfile} DEPLOY_LOCAL=1 COMPOSE_PROJECT_NAME={project}', flush=True)
            docker('exec', '-T', 'caddy', 'caddy', 'validate', '--config', '/etc/caddy/Caddyfile')
            docker('cp', 'caddy:/data/caddy/pki/authorities/local/root.crt', str(work / 'ca.crt'))
            port = values['LOCAL_HTTPS_PORT']

            def request(host, path, form=None, cookie=None, expected=200, extra_headers=(), bearer=None, method=None, json_body=None):
                command = ['curl', '-4', '--silent', '--show-error', '--cacert', str(work / 'ca.crt'), '--noproxy', '*',
                           '--connect-to', f'{host}:443:127.0.0.1:{port}', '--max-time', '30', '-D', str(work / 'headers'),
                           '-o', str(work / 'body'), '-w', '%{http_code}']
                if form is not None:
                    command += ['--data-binary', '@-', '-H', 'Content-Type: application/x-www-form-urlencoded', '-H', f'Origin: https://{host}']
                if method is not None:
                    command += ['--request', method]
                if json_body is not None:
                    command += ['--data-binary', '@-', '-H', 'Content-Type: application/json']
                if cookie:
                    (work / 'cookie-header').write_text('Cookie: ah_refresh=' + cookie)
                    command += ['-H', '@' + str(work / 'cookie-header')]
                if bearer:
                    (work / 'bearer-header').write_text('Authorization: Bearer ' + bearer)
                    command += ['-H', '@' + str(work / 'bearer-header')]
                for header in extra_headers:
                    command += ['-H', header]
                command += [f'https://{host}{path}']
                payload = json_body.encode() if json_body is not None else urllib.parse.urlencode(form).encode() if form else None
                result = run(command, input=payload)
                status = int(result.stdout)
                if status != expected:
                    body = json.loads((work / 'body').read_text()) if (work / 'body').read_text().startswith('{') else {}
                    raise RuntimeError(f'{host}{path}: expected {expected}, got {status} {body.get("code", "")}')
                return (work / 'body').read_text(), (work / 'headers').read_text()

            def baseline(headers, spa=False):
                lowered = headers.lower()
                assert 'x-content-type-options: nosniff' in lowered
                assert 'referrer-policy: strict-origin-when-cross-origin' in lowered
                assert 'x-frame-options: deny' in lowered
                if spa:
                    assert 'strict-transport-security:' in lowered
                else:
                    assert "frame-ancestors 'none'" in lowered

            body, headers = request('core.localhost', '/api/v1/health')
            baseline(headers)
            assert json.loads(body)['status'] == 'UP'
            print('PASS Caddy HTTPS health UP (trusted internal CA, no -k)', flush=True)
            for host in ('clubs.localhost', 'clubsadmin.localhost'):
                body, headers = request(host, '/api/v1/branding')
                baseline(headers)
                assert json.loads(body)['club']['slug'] == 'canic'
            print('PASS branding resolves canic by each preserved front Host', flush=True)
            for host, app in (('id.localhost', 'id'), ('clubs.localhost', 'clubs'), ('clubsadmin.localhost', 'clubsadmin')):
                for path in ('/', '/a/spa/deep-link', '/index.html'):
                    body, headers = request(host, path)
                    baseline(headers, spa=True)
                    assert 'cache-control: no-cache' in headers.lower()
                    assert f'<h1>AgilityHub {app}</h1>' in body
            print('PASS three SPAs: /, /index.html and deep links serve index with Cache-Control: no-cache', flush=True)
            body, headers = request('clubs.localhost', '/oauth2/token',
                                    {'grant_type': 'password', 'client_id': 'clubs-app',
                                     'username': 'admin@example.test', 'password': values['SEED_PASSWORD']})
            baseline(headers)
            assert 'no-store' in headers.lower()
            assert 'refresh_token' not in json.loads(body)

            def refresh_cookie(headers):
                cookies = http.cookies.SimpleCookie()
                for line in headers.splitlines():
                    if line.lower().startswith('set-cookie:'):
                        cookies.load(line.split(':', 1)[1].strip())
                cookie = cookies['ah_refresh']
                assert not cookie['domain'] and cookie['secure'] and cookie['httponly']
                assert cookie['samesite'] == 'Strict' and cookie['path'] == '/oauth2/token'
                return cookie.value

            if args.security_only:
                probe, _ = request('clubs.localhost', '/api/v1/me', expected=400, method='PATCH', json_body='{',
                                   bearer=json.loads(body)['access_token'])
                trace = json.loads(probe)['traceId']
                records = []
                for attempt in range(20):
                    raw = docker('logs', '--no-log-prefix', '--no-color', 'core', text=True).stdout
                    for line in raw.splitlines():
                        if line.startswith('{'):
                            record = json.loads(line)
                            if record.get('traceId') == trace and all(record.get(key) not in (None, '', '-') for key in ('clubId', 'accountId')):
                                records.append(record)
                    if records:
                        break
                    time.sleep(0.1)
                assert records, 'structured request log with the response trace id and both trusted identity ids'
                print('PASS JSON request log ' + json.dumps(records[-1]), flush=True)

            initial = refresh_cookie(headers)
            body, headers = request('clubs.localhost', '/oauth2/token',
                                    {'grant_type': 'refresh_token', 'client_id': 'clubs-app'}, initial)
            assert refresh_cookie(headers) != initial and 'refresh_token' not in json.loads(body)
            print('PASS proxy refresh rotates host-only ah_refresh; Secure HttpOnly SameSite=Strict Path=/oauth2/token', flush=True)
            request('clubs.localhost', '/oauth2/token', {'grant_type': 'refresh_token', 'client_id': 'clubs-app'}, initial, expected=400)
            print('PASS old refresh cookie rejected through Caddy', flush=True)
            request('core.localhost', '/internal/domains/allowed?host=unknown.invalid', expected=404)
            denied_tls = run(['curl', '--silent', '--show-error', '--cacert', str(work / 'ca.crt'), '--noproxy', '*',
                              '--connect-to', f'unapproved.localhost:443:127.0.0.1:{port}', '--max-time', '10',
                              'https://unapproved.localhost/'], check=False)
            assert denied_tls.returncode != 0
            body, headers = request('approved.localhost', '/')
            assert '<h1>AgilityHub clubs</h1>' in body
            assert 'cache-control: no-cache' in headers.lower()
            seen = json.loads(docker('exec', '-T', 'ask-stub', 'python', '-c',
                'import urllib.request; print(urllib.request.urlopen("http://localhost:8080/seen").read().decode())', text=True).stdout)
            assert '/internal/domains/allowed?host=approved.localhost' in seen
            assert '/internal/domains/allowed?host=unapproved.localhost' in seen
            print('PASS TLS certificate issued only for approved.localhost; real bridge forwards host=; other host denied', flush=True)
            # Unauthenticated Mongo reads must fail despite working authenticated startup.
            unauth = docker('exec', '-T', 'mongo', 'mongosh', '--quiet', '--eval',
                            'db.getSiblingDB("agilityhub").clubs.findOne()', check=False)
            assert unauth.returncode != 0
            print('PASS Mongo rejects unauthenticated reads; internal ask route is not public', flush=True)
            if args.security_only:
                own = 'https://clubsadmin.localhost'
                _, headers = request('clubs.localhost', '/api/v1/branding', extra_headers=(f'Origin: {own}',))
                assert 'access-control-allow-origin: ' + own in headers.lower()
                assert 'access-control-allow-credentials:' not in headers.lower()
                _, headers = request('clubs.localhost', '/api/v1/branding', expected=403,
                                     extra_headers=('Origin: https://unknown.example.test',))
                assert 'access-control-allow-origin:' not in headers.lower()
                print('PASS HTTPS API/token/SPA headers and own/unknown origins through Caddy', flush=True)
                return
            # Local S3 administration is not the production cron principal.
            helper('import os,boto3,json; s=boto3.client("s3",endpoint_url=os.environ["BACKUP_S3_ENDPOINT"],'
                   'aws_access_key_id=os.environ["BACKUP_S3_ACCESS_KEY"],aws_secret_access_key=os.environ["BACKUP_S3_SECRET_KEY"]);'
                   's.create_bucket(Bucket=os.environ["BACKUP_S3_BUCKET"]);'
                   "s.put_bucket_lifecycle_configuration(Bucket=os.environ['BACKUP_S3_BUCKET'], LifecycleConfiguration="
                   + repr(json.loads((ROOT / 'deploy/backup/lifecycle.json').read_text())) + ");"
                   "assert s.get_bucket_lifecycle_configuration(Bucket=os.environ['BACKUP_S3_BUCKET'])['Rules'][0]['Expiration']['Days']==30")
            # Enforce the runbook's split against real MinIO IAM; neither fixture identity is root.
            mc_image = 'agilityhub-mc:2025-08-13-local'
            if not args.skip_helper_builds:
                run(['docker', 'build', '-t', mc_image, '-f', str(ROOT / 'deploy/local/Dockerfile.mc'),
                     str(ROOT / 'deploy/local')], env=environment)
            writer_policy = json.loads((ROOT / 'deploy/backup/writer-policy.json').read_text().replace('example-backups', values['BACKUP_S3_BUCKET']))
            reader_policy = json.loads(json.dumps(writer_policy).replace('s3:PutObject', 's3:GetObject'))
            (work / 'writer-policy.json').write_text(json.dumps(writer_policy))
            (work / 'reader-policy.json').write_text(json.dumps(reader_policy))
            admin_env = dict(environment, MC_HOST_fixture='http://' + values['BACKUP_S3_ACCESS_KEY'] + ':'
                             + values['BACKUP_S3_SECRET_KEY'] + '@minio:9000', WRITER_USER=writer_user,
                             WRITER_PASSWORD=writer_password, READER_USER=reader_user, READER_PASSWORD=reader_password)
            run(['docker', 'run', '--rm', '--network', project + '_default',
                 '-e', 'MC_HOST_fixture', '-e', 'WRITER_USER', '-e', 'WRITER_PASSWORD', '-e', 'READER_USER', '-e', 'READER_PASSWORD',
                 '-v', str(work) + ':/fixture:ro', '--entrypoint', '/bin/sh', mc_image, '-ec',
                 'mc admin user add fixture "$WRITER_USER" "$WRITER_PASSWORD" >/dev/null; '
                 'mc admin user add fixture "$READER_USER" "$READER_PASSWORD" >/dev/null; '
                 'mc admin policy create fixture backup-writer /fixture/writer-policy.json >/dev/null; '
                 'mc admin policy create fixture backup-reader /fixture/reader-policy.json >/dev/null; '
                 'mc admin policy attach fixture backup-writer --user "$WRITER_USER" >/dev/null; '
                 'mc admin policy attach fixture backup-reader --user "$READER_USER" >/dev/null'], env=admin_env)
            print('PASS distinct MinIO writer Put/List and recovery Get/List principals provisioned', flush=True)
            helper("import os; assert 'BACKUP_AGE_IDENTITY' not in os.environ; assert os.environ['BACKUP_AGE_RECIPIENT'].startswith('age1')")
            print('PASS MinIO lifecycle is 30 days; normal backup container has only the public recipient', flush=True)
            probe = docker('run', '--rm', '--no-deps', '-d', '--entrypoint', 'sleep', 'backup', '120', text=True).stdout.strip()
            info = json.loads(run(['docker', 'inspect', probe], text=True).stdout)[0]
            assert info['HostConfig']['Memory'] == 1024 ** 3
            volume = next(mount['Name'] for mount in info['Mounts']
                          if mount['Destination'] == '/work' and mount['Type'] == 'volume')
            run(['docker', 'stop', '-t', '1', probe])
            for attempt in range(50):
                if run(['docker', 'volume', 'inspect', volume], check=False).returncode != 0:
                    break
                time.sleep(0.1)
            else:
                raise AssertionError('Anonymous ops work volume survived its container')
            print('PASS ops memory is 1 GiB and its anonymous work volume disappears with the container', flush=True)
            output = script('backup-mongo')
            key = re.search(r'BACKUP_OK key=(\S+)', output).group(1)
            # The writer may list and put, never read: the S3 client error is the denied HeadObject/GetObject.
            script('restore-mongo', '--verify', wrong_writer=True, expected=1, failure='FAILED: ClientError')
            print('PASS writer environment cannot verify a backup (GetObject denied)', flush=True)
            script('restore-mongo', '--verify')
            script('restore-mongo', '--verify', key)
            saved_identity = environment.pop('BACKUP_AGE_IDENTITY')
            script('restore-mongo', '--verify', expected=2)
            environment['BACKUP_AGE_IDENTITY'] = saved_identity
            for missing in ('BACKUP_AGE_RECIPIENT', 'BACKUP_S3_BUCKET'):
                old = values[missing]
                values[missing] = ''
                write_env()
                script('backup-mongo', expected=2)
                values[missing] = old
                write_env()
            print('PASS missing recipient and bucket fail before container/dump/upload', flush=True)
            bad_key = 'mongo/20000101T000000Z-' + secrets.token_hex(16) + '.tar'
            mismatch_key = 'mongo/20000102T000000Z-' + secrets.token_hex(16) + '.tar'
            # This helper receives the restore identity only for this explicit verification.
            fixture = ROOT / 'deploy/backup/proof.py'
            environment['LOCAL_MONGODB_URI'] = values['LOCAL_MONGODB_URI']
            proof_command = compose + ['run', '--rm', '--no-deps', '-T', '-e', 'BACKUP_AGE_IDENTITY', '-e', 'LOCAL_MONGODB_URI',
                           '-v', str(fixture) + ':/opt/backup/proof.py:ro', '--entrypoint', '/opt/backup/bin/python',
                           'backup', '/opt/backup/proof.py', key, bad_key, mismatch_key]
            proof = run(proof_command, env=environment, text=True, check=False)
            record(proof_command, proof)
            print('COMMAND isolated proof.py -> exit ' + str(proof.returncode), flush=True)
            print(proof.stdout, flush=True)
            if proof.returncode:
                print(proof.stderr[-3000:], flush=True)
                raise RuntimeError('Concurrent-write/negative proof failed')
            script('restore-mongo', '--verify', bad_key, expected=1)
            print('PASS authenticated decryption rejects tampered ciphertext before Mongo starts', flush=True)
            script('restore-mongo', '--verify', mismatch_key, expected=1)
            print('PASS authenticated false count fails verification', flush=True)
            script('restore-mongo', expected=2)
            print('PASS restore requires --verify and has no live destination', flush=True)
            print('E11-T04 ALL CHECKS PASSED', flush=True)
        finally:
            cleanup = docker('down', '--volumes', '--remove-orphans', check=False)
            if cleanup.returncode:
                raise RuntimeError('Disposable project cleanup failed')
            for arguments in (['ps', '-aq'], ['network', 'ls', '-q'], ['volume', 'ls', '-q']):
                remaining = run(['docker', *arguments, '--filter', 'label=com.docker.compose.project=' + project], text=True).stdout.strip()
                assert not remaining, 'Disposable project resources remain'
            print('CLEANUP disposable project, volumes and private credentials removed', flush=True)


if __name__ == '__main__':
    main()
