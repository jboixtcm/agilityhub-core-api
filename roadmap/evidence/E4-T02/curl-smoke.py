#!/usr/bin/env python3
"""Disposable local Compose rehearsal using the freshly built jar and fictional seeds."""
import base64
import datetime
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import tempfile
import uuid
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[3]

def port():
    with socket.socket() as connection:
        connection.bind(('127.0.0.1', 0))
        return str(connection.getsockname()[1])

def clean(text):
    text = re.sub(r'eyJ[A-Za-z0-9_.-]+', 'eyJ…[truncated]', text)
    text = re.sub(r'\b[0-9a-fA-F]{24,}\b', lambda m: m[0][:6] + '…[truncated]', text)
    for value in private_values:
        text = text.replace(value, '[redacted]')
    return text

private_values = []
with tempfile.TemporaryDirectory(prefix='e4-t02-smoke-') as temporary:
    folder = Path(temporary)
    environment = dict(os.environ)
    environment.update(SERVER_PORT=port(), MONGO_PORT=port(), MONGODB_DATABASE='planning_smoke',
                       MONGODB_REPLICA_SET='rs0', SEED_PASSWORD=secrets.token_urlsafe(24),
                       OIDC_MASTER_KEY=base64.b64encode(secrets.token_bytes(32)).decode(),
                       SIGNUP_CAPABILITY_KEY=base64.b64encode(secrets.token_bytes(32)).decode())
    private_values.extend(environment[k] for k in ('SEED_PASSWORD', 'OIDC_MASTER_KEY', 'SIGNUP_CAPABILITY_KEY'))
    jar = next((ROOT / 'target').glob('agilityhub-core-api-*.jar'))
    override = folder / 'compose.yaml'
    override.write_text('services:\n  api:\n    image: agilityhub-e3-smoke:local\n    volumes:\n      - ' + str(jar) + ':/app/app.jar:ro\n' +
                        '    environment:\n      SEED_PASSWORD: ${SEED_PASSWORD}\n')
    compose = ['docker', 'compose', '--env-file', '/dev/null', '--project-name', 'e4t02-smoke-' + str(os.getpid()),
               '-f', str(ROOT / 'compose.yaml'), '-f', str(override)]
    def run(arguments):
        result = subprocess.run(arguments, cwd=ROOT, env=environment, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        print(clean(result.stdout), end='', flush=True)
        if result.returncode:
            raise RuntimeError('Command failed: ' + ' '.join(arguments[:4]) + ' exit=' + str(result.returncode))
    base = 'http://127.0.0.1:' + environment['SERVER_PORT']
    auth = folder / 'curl-auth.conf'
    auth.write_text('header = "Host: admin.example.test"\n')
    auth.chmod(0o600)
    def call(method, path, body=None, form=None, key=False, label=None):
        arguments = ['curl', '--silent', '--show-error', '--fail-with-body', '--write-out', '\n%{http_code}',
                     '--config', str(auth), '-X', method, base + path]
        payload = None
        if body is not None:
            arguments += ['-H', 'Content-Type: application/json', '--data-binary', '@-']
            payload = json.dumps(body)
        if form is not None:
            import urllib.parse
            arguments += ['-H', 'Content-Type: application/x-www-form-urlencoded', '--data-binary', '@-']
            payload = urllib.parse.urlencode(form)
        if key:
            arguments += ['-H', 'Idempotency-Key: ' + str(uuid.uuid4())]
        result = subprocess.run(arguments, input=payload, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if result.returncode:
            raise RuntimeError(clean(result.stdout + result.stderr))
        response, status = result.stdout.rsplit('\n', 1)
        assert 200 <= int(status) < 300, status
        value = json.loads(response)
        if label:
            print('curl ' + method + ' ' + path + ' -> HTTP ' + status + ' ' + label(value), flush=True)
        return value
    try:
        run(compose + ['up', '-d', '--wait', 'mongo'])
        run(compose + ['run', '--rm', '--no-deps', 'api', '--core.command=club:apply', '/app/seeds/club-canic.yaml'])
        run(compose + ['run', '--rm', '--no-deps', 'api', '--core.command=seed:demo', '--club=canic', '--seed=42'])
        run(compose + ['up', '-d', '--wait', '--no-build', 'api'])
        call('GET', '/api/v1/health', label=lambda r: r['status'])
        token = call('POST', '/oauth2/token', form=dict(grant_type='password', client_id='clubs-admin',
                     username='admin@example.test', password=environment['SEED_PASSWORD']))['access_token']
        private_values.append(token)
        with auth.open('a') as stream:
            stream.write('header = "Authorization: Bearer ' + token + '"\n')
        levels = call('GET', '/api/v1/levels')['items']
        rings = call('GET', '/api/v1/rings')['items']
        instructors = call('GET', '/api/v1/instructors')['items']
        names = {item['code']: item['id'] for item in levels}
        template = call('POST', '/api/v1/week-templates', {'name': 'Planning smoke', 'kind': 'WEEKDAYS'}, label=lambda r: r['name'])
        path = '/api/v1/week-templates/' + template['id']
        template = call('POST', path + '/bands', dict(startTime='18:00', endTime='19:00'), label=lambda r: 'bands=' + str(len(r['bands'])))
        template = call('POST', path + '/classes', dict(bandId=template['bands'][0]['id'], dayOfWeek='MONDAY',
                        instructorIds=[instructors[0]['id']], ringId=rings[0]['id'], levelIds=[names['B'], names['C']]),
                        label=lambda r: 'displayDescription=' + r['classes'][0]['displayDescription'])
        assert template['classes'][0]['displayDescription'] == 'B+C'
        coverage = call('GET', '/api/v1/coverage?templateId=' + template['id'], label=lambda r: 'scope=' + r['scope'] + ' levels=' + str(len(r['levels'])))
        today = datetime.datetime.now(ZoneInfo('Europe/Madrid')).date()
        start = today - datetime.timedelta(days=today.weekday()) + datetime.timedelta(weeks=1)
        week = call('POST', '/api/v1/weeks', {'startDate': start.isoformat()}, label=lambda r: r['state'])
        generated = call('POST', '/api/v1/weeks/' + week['id'] + '/generation', {'weekdayTemplateId': template['id']}, key=True,
                         label=lambda r: 'classCount=' + str(r['classCount']))
        assert generated['classCount'] == 1
        detail = call('GET', '/api/v1/weeks/' + week['id'], label=lambda r: 'state=' + r['state'])
        assert detail['state'] == 'GENERATED'
        listing = call('GET', '/api/v1/weeks', label=lambda r: 'classCounts=' + json.dumps(r['items'][0]['classCounts']))
        assert listing['items'][0]['classCounts']['draft'] == 1
        print('PASS: template -> band -> B+C class -> coverage -> generation -> GENERATED / 1 draft class', flush=True)
    finally:
        run(compose + ['down', '-v', '--remove-orphans'])
        print('Disposable Compose containers, volumes and network removed.', flush=True)
