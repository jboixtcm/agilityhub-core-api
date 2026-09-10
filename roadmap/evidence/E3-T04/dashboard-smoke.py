#!/usr/bin/env python3
"""Disposable E3-T04 seeded Mongo/API curl rehearsal; no existing stack is modified."""
import base64
import json
import os
from pathlib import Path
import secrets
import shlex
import socket
import subprocess
import tempfile
import time
import urllib.parse

root = Path(__file__).resolve().parents[3]

def port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]

def command(args, **kwargs):
    return subprocess.run(args, cwd=root, check=True, text=True, capture_output=True, timeout=180, **kwargs)

name = 'e3-t04-' + secrets.token_hex(4)
mongo_port, api_port, management_port = port(), port(), port()
api = None
with tempfile.TemporaryDirectory(prefix='e3-t04-') as temp:
    env = dict(os.environ, SPRING_PROFILES_ACTIVE='local', MONGODB_HOST='127.0.0.1', MONGODB_PORT=str(mongo_port),
               MONGODB_DATABASE='e3_t04_dashboard', MONGODB_REPLICA_SET='rs0', SEED_PASSWORD=secrets.token_urlsafe(24),
               OIDC_MASTER_KEY=base64.b64encode(secrets.token_bytes(32)).decode(),
               ATTACHMENT_LOCAL_DIRECTORY=temp + '/attachments', EXPORT_LOCAL_DIRECTORY=temp + '/exports',
               MAIL_LOCAL_DIRECTORY=temp + '/mail', SENDGRID_API_KEY='')
    try:
        command(['docker', 'run', '--rm', '-d', '--name', name, '-p', f'127.0.0.1:{mongo_port}:27017',
                 'mongo:7', '--replSet', 'rs0', '--bind_ip_all'])
        for attempt in range(60):
            try:
                command(['docker', 'exec', name, 'mongosh', '--quiet', '--eval', 'db.adminCommand({ping:1})'])
                break
            except subprocess.CalledProcessError:
                time.sleep(1)
        command(['docker', 'exec', name, 'mongosh', '--quiet', '--eval',
                 'rs.initiate({_id:"rs0",members:[{_id:0,host:"localhost:27017"}]})'])
        for attempt in range(60):
            if 'true' in command(['docker', 'exec', name, 'mongosh', '--quiet', '--eval', 'db.hello().isWritablePrimary']).stdout:
                break
            time.sleep(1)
        for args in (['bin/core', 'club:apply', 'seeds/club-canic.yaml'], ['bin/core', 'seed:demo', '--club=canic']):
            output = command(args, env=env)
            print('$ ' + shlex.join(args), '\nexit code:', output.returncode)
        # Seed definitions intentionally start ONBOARDING. Activate only this disposable fictional tenant for login.
        command(['docker', 'exec', name, 'mongosh', '--quiet', 'e3_t04_dashboard', '--eval',
                 'db.clubs.updateOne({slug:"canic"},{$set:{status:"ACTIVE"}})'])
        jars = list((root / 'target').glob('agilityhub-core-api-*.jar'))
        assert len(jars) == 1
        with open(Path(temp) / 'api.log', 'w') as server_log:
            api = subprocess.Popen(['java', '-jar', str(jars[0]), f'--server.port={api_port}',
                                    f'--management.server.port={management_port}', '--shared.scheduling.enabled=false'],
                                   cwd=root, env=env, stdout=server_log, stderr=subprocess.STDOUT, text=True)
            base = f'http://127.0.0.1:{api_port}'
            for attempt in range(90):
                response = subprocess.run(['curl', '-4', '-fsS', '--max-time', '2', base + '/api/v1/health'], capture_output=True, text=True)
                if response.returncode == 0:
                    break
                if api.poll() is not None:
                    raise RuntimeError('Disposable API stopped during startup')
                time.sleep(1)
            else:
                raise RuntimeError('Disposable API did not become healthy')
            print('$ curl -4 -fsS ' + base + '/api/v1/health\nexit code: 0\n' + response.stdout)
            form = urllib.parse.urlencode(dict(grant_type='password', client_id='clubs-admin', username='admin@example.test',
                                               password=env['SEED_PASSWORD'], scope='openid profile email memberships offline_access'))
            login = command(['curl', '-4', '-fsS', '--max-time', '20', '-H', 'Host: app.agilitycanic.cat',
                             '-H', 'Content-Type: application/x-www-form-urlencoded', '--data-binary', '@-', base + '/oauth2/token'], input=form)
            token = json.loads(login.stdout)['access_token']
            print('Authenticated fictional seeded administrator; credentials and token omitted.')
            results = {}
            for route in ('/dashboard', '/dashboard/counters'):
                # Keep the token off argv and out of every evidence log.
                config = 'header = "Authorization: Bearer ' + token + '"\n'
                args = ['curl', '-4', '-fsS', '--max-time', '20', '-H', 'Host: app.agilitycanic.cat', '--config', '-', base + '/api/v1' + route]
                response = command(args, input=config)
                body = json.loads(response.stdout)
                results[route] = body
                print('$ ' + shlex.join(args) + '\n# stdin supplies the authorization header (token omitted)\nexit code: 0')
                print(json.dumps(body, indent=2, ensure_ascii=False))
            dashboard = results['/dashboard']
            assert dashboard['kpis']['activeMembers']['value'] == 184
            assert dashboard['kpis']['pendingSignups']['value'] == 3
            assert dashboard['kpis']['pendingSignups']['warnDays'] == 2
            assert dashboard['kpis']['classOccupancy'] == dict(percent=None, booked=0, capacity=0, waitingTotal=0)
            assert dashboard['dogsByLevel']['totalActiveDogs'] == 242
            assert [row['total'] for row in dashboard['dogsByLevel']['levels'] if row['total']] == [24, 43, 39, 42, 35, 31, 16, 12]
            assert results['/dashboard/counters'] == dict(pendingSignups=3, pendingRequests=0, followUpUnread=0)
            print('PASS: seeded census, nullable occupancy, level totals and counters.')
    finally:
        if api is not None:
            api.terminate()
            try:
                api.wait(timeout=20)
            except subprocess.TimeoutExpired:
                api.kill()
                api.wait(timeout=10)
        subprocess.run(['docker', 'rm', '-f', '-v', name], cwd=root, capture_output=True, timeout=30)
        print('Removed disposable API, Mongo container and temporary seed files; existing stack preserved.')
