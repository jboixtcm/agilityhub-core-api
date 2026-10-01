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
import socket
import subprocess
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
    parser.add_argument('--security-only', action='store_true', help='E11-T03: headers/CORS/logs; skip backup helpers')
    args = parser.parse_args()
    os.umask(0o077)
    with tempfile.TemporaryDirectory(prefix='e11-t04-') as directory:
        work = Path(directory)
        envfile = work / 'local.env'
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
                    'BILLING_BANK_KEY'):
            values[key] = base64.b64encode(secrets.token_bytes(32)).decode()
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

        write_env()
        compose = ['docker', 'compose', '--env-file', str(envfile), '-p', project,
                   '-f', str(ROOT / 'deploy/compose.prod.yaml'), '-f', str(ROOT / 'deploy/compose.prod.local.yaml')]

        if args.security_only:
            override = work / 'security.yaml'
            override.write_text('services:\n  core:\n    environment:\n      SPRING_PROFILES_ACTIVE: local,structured-logs\n      LOGGING_STRUCTURED_FORMAT_CONSOLE: com.agilityhub.core.configuration.PrivacyLogFormatter\n')
            compose += ['-f', str(override)]

        def docker(*arguments, **kwargs):
            return run(compose + list(arguments), env=environment, **kwargs)

        def script(name, *arguments, expected=0):
            result = run([str(ROOT / 'bin' / name), *arguments], env=environment, check=False, text=True)
            print(f'COMMAND bin/{name} {" ".join(arguments)} -> exit {result.returncode}', flush=True)
            print(result.stdout.strip(), flush=True)
            if result.returncode != expected:
                print(result.stderr[-1500:], flush=True)
                raise RuntimeError(f'{name}: expected exit {expected}')
            if expected:
                print(result.stderr.strip(), flush=True)
            return result.stdout

        def helper(code):
            return docker('run', '--rm', '--no-deps', '-T', '--entrypoint', '/opt/backup/bin/python',
                          'backup', '-c', code, text=True).stdout

        try:
            resolved = json.loads(docker('config', '--format', 'json', text=True).stdout)
            assert resolved['services']['core']['environment'].get('BILLING_BANK_KEY') == values['BILLING_BANK_KEY'], \
                'BILLING_BANK_KEY must reach Core from the deployment environment'
            print('PASS deployment forwards the bank encryption key to Core', flush=True)
            if not args.security_only:
                print('COMMAND ' + shlex.join(compose + ['build', 'backup', 'minio']), flush=True)
                build = docker('build', 'backup', 'minio', check=False, text=True)
                if build.returncode:
                    print(build.stderr[-2500:], flush=True)
                    raise RuntimeError('Backup image build failed')
                generated = docker('run', '--rm', '--no-deps', '-T', '--entrypoint', 'age-keygen', 'backup', text=True)
                values['BACKUP_ENCRYPTION_KEY'] = next(line for line in generated.stdout.splitlines() if line.startswith('AGE-SECRET-KEY-'))
                write_env()
            print('COMMAND ' + shlex.join(compose + ['up', '-d', '--wait', '--wait-timeout', '420']), flush=True)
            targets = ['caddy'] if args.security_only else []
            started = docker('up', '-d', '--wait', '--wait-timeout', '420', *targets, check=False, text=True)
            print(started.stderr.strip(), flush=True)
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
                           '--resolve', f'{host}:{port}:127.0.0.1', '--max-time', '30', '-D', str(work / 'headers'),
                           '-o', str(work / 'body'), '-w', '%{http_code}']
                if form is not None:
                    command += ['--data-binary', '@-', '-H', 'Content-Type: application/x-www-form-urlencoded', '-H', f'Origin: https://{host}:{port}']
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
                command += [f'https://{host}:{port}{path}']
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
                body, headers = request(host, '/a/spa/deep-link')
                baseline(headers, spa=True)
                assert f'<h1>AgilityHub {app}</h1>' in body
            print('PASS all three mounted SPA index fixtures and deep-link fallback', flush=True)
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
                              '--resolve', f'unapproved.localhost:{port}:127.0.0.1', '--max-time', '10',
                              f'https://unapproved.localhost:{port}/'], check=False)
            assert denied_tls.returncode != 0
            print('PASS unapproved on-demand TLS host fails closed while E10 approval is unavailable', flush=True)
            # Unauthenticated Mongo reads must fail despite working authenticated startup.
            unauth = docker('exec', '-T', 'mongo', 'mongosh', '--quiet', '--eval',
                            'db.getSiblingDB("agilityhub").clubs.findOne()', check=False)
            assert unauth.returncode != 0
            print('PASS Mongo rejects unauthenticated reads; internal ask route is not public', flush=True)
            if args.security_only:
                own = f'https://clubsadmin.localhost:{port}'
                _, headers = request('clubs.localhost', '/api/v1/branding', extra_headers=(f'Origin: {own}',))
                assert 'access-control-allow-origin: ' + own in headers.lower()
                assert 'access-control-allow-credentials:' not in headers.lower()
                _, headers = request('clubs.localhost', '/api/v1/branding', expected=403,
                                     extra_headers=('Origin: https://unknown.example.test',))
                assert 'access-control-allow-origin:' not in headers.lower()
                print('PASS HTTPS API/token/SPA headers and own/unknown origins through Caddy', flush=True)
                return
            helper('import os,boto3; s=boto3.client("s3",endpoint_url=os.environ["BACKUP_S3_ENDPOINT"],'
                   'aws_access_key_id=os.environ["BACKUP_S3_ACCESS_KEY"],aws_secret_access_key=os.environ["BACKUP_S3_SECRET_KEY"]);'
                   's.create_bucket(Bucket=os.environ["BACKUP_S3_BUCKET"])')
            output = script('backup-mongo')
            key = re.search(r'BACKUP_OK key=(\S+)', output).group(1)
            script('restore-mongo', '--verify')
            script('restore-mongo', '--verify', key)
            for missing in ('BACKUP_ENCRYPTION_KEY', 'BACKUP_S3_BUCKET'):
                old = values[missing]
                values[missing] = ''
                write_env()
                script('backup-mongo', expected=2)
                values[missing] = old
                write_env()
            print('PASS missing key and bucket fail in host preflight before a container/dump/upload', flush=True)
            bad_key = 'mongo/20000101T000000Z-' + secrets.token_hex(16) + '.tar.age'
            helper('import os,boto3; s=boto3.client("s3",endpoint_url=os.environ["BACKUP_S3_ENDPOINT"],'
                   'aws_access_key_id=os.environ["BACKUP_S3_ACCESS_KEY"],aws_secret_access_key=os.environ["BACKUP_S3_SECRET_KEY"]);'
                   f'b=os.environ["BACKUP_S3_BUCKET"]; d=bytearray(s.get_object(Bucket=b,Key={key!r})["Body"].read());'
                   f'd[-1]^=1; s.put_object(Bucket=b,Key={bad_key!r},Body=bytes(d))')
            script('restore-mongo', '--verify', bad_key, expected=1)
            print('PASS authenticated decryption rejects a tampered archive before Mongo starts', flush=True)
            mismatch_key = 'mongo/20000102T000000Z-' + secrets.token_hex(16) + '.tar.age'
            helper(f'''
import io, json, os, pathlib, subprocess, tarfile, tempfile, boto3
s = boto3.client('s3', endpoint_url=os.environ['BACKUP_S3_ENDPOINT'],
                 aws_access_key_id=os.environ['BACKUP_S3_ACCESS_KEY'],
                 aws_secret_access_key=os.environ['BACKUP_S3_SECRET_KEY'])
with tempfile.TemporaryDirectory(dir='/work') as directory:
    p = pathlib.Path(directory)
    (p/'key').write_text(os.environ['BACKUP_ENCRYPTION_KEY']+'\\n')
    s.download_file(os.environ['BACKUP_S3_BUCKET'], {key!r}, str(p/'original.age'))
    subprocess.run(['age','-d','-i',str(p/'key'),'-o',str(p/'original.tar'),str(p/'original.age')], check=True)
    with tarfile.open(p/'original.tar') as original:
        manifest=json.load(original.extractfile('manifest.json'))
        collection=next(k for k in manifest['counts'] if k.startswith('agilityhub.'))
        manifest['counts'][collection]+=1
        dump=original.extractfile('dump.archive.gz').read()
    with tarfile.open(p/'changed.tar','w') as changed:
        for name, data in [('manifest.json',json.dumps(manifest).encode()),('dump.archive.gz',dump)]:
            member=tarfile.TarInfo(name); member.size=len(data); changed.addfile(member,io.BytesIO(data))
    recipient=subprocess.check_output(['age-keygen','-y',str(p/'key')]).decode().strip()
    subprocess.run(['age','-r',recipient,'-o',str(p/'changed.age'),str(p/'changed.tar')],check=True)
    s.upload_file(str(p/'changed.age'),os.environ['BACKUP_S3_BUCKET'],{mismatch_key!r})
''')
            script('restore-mongo', '--verify', mismatch_key, expected=1)
            print('PASS authenticated archive with a wrong source count fails verification', flush=True)
            script('restore-mongo', expected=2)
            print('PASS restore refuses any invocation without --verify; no live target exists', flush=True)
            print('E11-T04 ALL CHECKS PASSED', flush=True)
        finally:
            docker('down', '--volumes', '--remove-orphans', check=False)
            print('CLEANUP disposable project, volumes and private credentials removed', flush=True)


if __name__ == '__main__':
    main()
