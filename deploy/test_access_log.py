"""E9-T06 (S16 R-16-11): the build session stream's `?access_token=` never reaches a Caddy access log.

CFG-07 (owner decision of 13 Nov 2026): every public site logs its requests to one rolled file, with the query string dropped
from every URL logged (the request's, its Referer's and a Location answer's)."""
from pathlib import Path
import json
import re
import socket
import subprocess
import tempfile
import time
import unittest
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
IMAGE = 'caddy:2.10.2-alpine'
TOKEN = 'eyJfictional.access.token'
LOG_FILE = '/var/log/caddy/access.log'
PRODUCTION_ENV = {'ACME_EMAIL': 'ops@example.test', 'ID_HOST': 'id.example.test', 'CLUBS_HOST': 'clubs.example.test',
                  'ADMIN_HOST': 'admin.example.test', 'CORE_HOST': 'core.example.test',
                  'CADDY_ASK_UPSTREAM': 'core:8080', 'CADDY_APP_UPSTREAM': 'core:8080'}
# What the filter must keep out of the log: fictional codes, signatures and credentials.
SECRETS = (TOKEN, 'fictional-code', 'fictional-handoff', 'fictional-signature', 'fictional-refresh', 'fictional-cookie',
           'eyJfictional.bearer')


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


class AccessLogTest(unittest.TestCase):
    def setUp(self):
        self.caddy = (ROOT / 'deploy/Caddyfile').read_text()
        self.snippet = self.caddy[self.caddy.index('(access_log) {'):self.caddy.index('(planned_maintenance)')]

    def test_E9_T06_no_site_logs_without_the_filter(self):
        # The only `log` directive is the filtered snippet's. CFG-07 pass 2: it drops the whole query string (the stream's
        # access_token with it) of the request URI, the Referer and a Location answer, instead of deleting access_token alone.
        self.assertEqual(1, len(re.findall(r'^\s*log\b', self.caddy, re.M)))
        fields = re.search(r'fields \{([^}]*)\}', self.snippet).group(1)
        self.assertEqual(['request>uri', 'request>headers>Referer', 'resp_headers>Location'],
                         re.findall(r'^\s*(\S+) regexp \\\?\.\*\$ ""\s*$', fields, re.M))
        self.assertNotIn('delete access_token', self.snippet)

    def test_CFG_07_every_public_site_imports_the_rolled_log(self):
        self.assertRegex(self.snippet, r'output file /var/log/caddy/access\.log \{\s*roll_size 50MiB\s*roll_keep 30\s*'
                                       r'roll_keep_for 30d\s*\}')
        spa = self.caddy[self.caddy.index('(spa) {'):self.caddy.index('{$ID_HOST} {')]
        core = self.caddy[self.caddy.index('{$CORE_HOST} {'):self.caddy.index('# Never published by Compose.')]
        catch_all = self.caddy[self.caddy.index('https:// {'):]
        for name, block in (('spa', spa), ('{$CORE_HOST}', core), ('https://', catch_all)):
            self.assertIn('import access_log', block, name)
        ask = self.caddy[self.caddy.index('http://127.0.0.1:9080 {'):self.caddy.index('(club_spa) {')]
        self.assertNotIn('access_log', ask)
        # The file lives in its own volume, never in the container log.
        compose = (ROOT / 'deploy/compose.prod.yaml').read_text()
        caddy = compose[compose.index('\n  caddy:\n'):compose.index('\n  backup:\n')]
        self.assertIn('\n      - caddy-logs:/var/log/caddy\n', caddy)
        self.assertRegex(compose, r'\nvolumes:\n(  \S+:\n)*  caddy-logs:\n')

    def test_E9_T06_production_caddyfile_still_validates(self):
        flags = [part for key, value in PRODUCTION_ENV.items() for part in ('-e', f'{key}={value}')]
        subprocess.run(['docker', 'run', '--rm', '-v', f'{ROOT / "deploy/Caddyfile"}:/etc/caddy/Caddyfile:ro', *flags, IMAGE,
                        'caddy', 'validate', '--config', '/etc/caddy/Caddyfile'], check=True, capture_output=True)

    def test_CFG_07_every_public_host_and_the_club_catch_all_write_the_rolled_file(self):
        # Caddy's own reading of the production file: each product host, the Core's host and every other (club) host has a logger,
        # and each of them writes the one rolled file through the filter; the internal ask listener has none.
        flags = [part for key, value in PRODUCTION_ENV.items() for part in ('-e', f'{key}={value}')]
        adapted = json.loads(subprocess.run(['docker', 'run', '--rm', '-v', f'{ROOT / "deploy/Caddyfile"}:/etc/caddy/Caddyfile:ro',
                                             *flags, IMAGE, 'caddy', 'adapt', '--config', '/etc/caddy/Caddyfile'],
                                            check=True, capture_output=True, text=True).stdout)
        servers = adapted['apps']['http']['servers']
        public = next(server for server in servers.values() if server['listen'] == [':443'])
        names = {host: loggers[0] for host, loggers in public['logs']['logger_names'].items()}
        self.assertEqual({'id.example.test', 'clubs.example.test', 'admin.example.test', 'core.example.test'}, set(names))
        loggers = [*names.values(), public['logs']['default_logger_name']]
        self.assertEqual(5, len(set(loggers)))
        logs = adapted['logging']['logs']
        for name in loggers:
            self.assertEqual({'output': 'file', 'filename': LOG_FILE, 'roll_size_mb': 50, 'roll_keep': 30, 'roll_keep_days': 30},
                             logs[name]['writer'], name)
            self.assertEqual({'request>uri', 'request>headers>Referer', 'resp_headers>Location'}, set(logs[name]['encoder']['fields']))
            self.assertIn(f'http.log.access.{name}', logs['default']['exclude'])
        self.assertEqual([None], [server.get('logs') for server in servers.values() if server['listen'] == [':9080']])

    def start(self, sites):
        # The real snippet in the production image, in front of fixed answers, as the sites import it. The file stays inside the
        # container (read with `docker exec`), so the test leaves no root-owned file on the runner.
        port = free_port()
        with tempfile.TemporaryDirectory(prefix='cfg07-caddy-') as directory:
            path = Path(directory) / 'Caddyfile'
            path.write_text('{\n admin off\n}\n' + self.snippet + sites)
            subprocess.run(['docker', 'run', '--rm', '-v', f'{path}:/etc/caddy/Caddyfile:ro', IMAGE,
                            'caddy', 'validate', '--config', '/etc/caddy/Caddyfile'], check=True, capture_output=True)
            container = subprocess.check_output(['docker', 'run', '--rm', '-d', '-p', f'127.0.0.1:{port}:8080',
                                                 '-v', f'{path}:/etc/caddy/Caddyfile:ro', IMAGE], text=True).strip()
        self.addCleanup(subprocess.run, ['docker', 'rm', '-f', container], check=True, capture_output=True)
        return port, container

    @staticmethod
    def get(port, route, host='members.example.test', headers=()):
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        request = urllib.request.Request(f'http://127.0.0.1:{port}{route}', headers={'Host': host, **dict(headers)})
        for attempt in range(50):
            try:
                try:
                    response = opener.open(request, timeout=1)
                except urllib.error.HTTPError as error:
                    response = error
                with response:
                    return response.status, response.headers, response.read()
            except (ConnectionError, urllib.error.URLError, TimeoutError):
                time.sleep(0.1)
        raise AssertionError('Caddy did not answer')

    @staticmethod
    def entries(container, count):
        text = ''
        for attempt in range(50):
            text = subprocess.run(['docker', 'exec', container, 'cat', LOG_FILE], capture_output=True, text=True).stdout
            if len([line for line in text.splitlines() if line.startswith('{')]) >= count:
                break
            time.sleep(0.1)
        return text, [json.loads(line) for line in text.splitlines() if line.startswith('{')]

    def test_E9_T06_real_caddy_drops_the_query_token(self):
        port, container = self.start('\nhttp://:8080 {\n import access_log\n respond "fictional stream" 200\n}\n')
        url = f'/api/v1/build-sessions/session-a/events?access_token={TOKEN}&lastEventId=3'
        self.assertEqual(b'fictional stream', self.get(port, url)[2])
        text, access = self.entries(container, 1)
        self.assertTrue(access, text)
        # CFG-07 pass 2: the whole query goes, `lastEventId` too (before: only access_token was deleted).
        self.assertEqual('/api/v1/build-sessions/session-a/events', access[-1]['request']['uri'])
        self.assertNotIn(TOKEN, text)
        self.assertNotIn('access_token', text)

    def test_CFG_07_real_caddy_logs_each_request_without_queries_or_credentials(self):
        sites = '''
http://:8080 {
    import access_log
    @signed path /files/f-1
    redir @signed /api/v1/attachments/files/f-1?expires=1&signature=fictional-signature 302
    @refresh path /oauth2/token
    header @refresh Set-Cookie "ah_refresh=fictional-refresh; Path=/oauth2/token; HttpOnly"
    respond "fictional answer" 200
}
'''
        port, container = self.start(sites)
        self.assertEqual(200, self.get(port, '/oidc/callback?code=fictional-code&state=fictional-state',
                                       headers=[('Referer', 'https://members.example.test/entrar?handoff=fictional-handoff')])[0])
        self.assertEqual(302, self.get(port, '/files/f-1?download=1')[0])
        self.assertEqual(200, self.get(port, '/oauth2/token', host='id.example.test',
                                       headers=[('Authorization', 'Bearer eyJfictional.bearer'), ('Cookie', 'ah_refresh=fictional-cookie')])[0])
        self.assertEqual(200, self.get(port, '/agenda', host='office.example.test')[0])
        text, access = self.entries(container, 4)
        self.assertEqual(4, len(access), text)
        for secret in SECRETS + ('?',):
            self.assertNotIn(secret, text.replace('\\u0026', '&'))
        by_uri = {entry['request']['uri']: entry for entry in access}
        self.assertEqual({'/oidc/callback', '/files/f-1', '/oauth2/token', '/agenda'}, set(by_uri))
        self.assertEqual(['https://members.example.test/entrar'], by_uri['/oidc/callback']['request']['headers']['Referer'])
        self.assertEqual(['/api/v1/attachments/files/f-1'], by_uri['/files/f-1']['resp_headers']['Location'])
        token = by_uri['/oauth2/token']
        self.assertEqual((['REDACTED'], ['REDACTED']), (token['request']['headers']['Authorization'], token['request']['headers']['Cookie']))
        self.assertEqual(['REDACTED'], token['resp_headers']['Set-Cookie'])
        # Who asked for what and when: the address, the host, the method, the status and the time stay.
        for entry in access:
            self.assertTrue(entry['request']['remote_ip'])
            self.assertIn(entry['request']['host'], ('members.example.test', 'id.example.test', 'office.example.test'))
            self.assertEqual('GET', entry['request']['method'])
            self.assertIn(entry['status'], (200, 302))
            self.assertIn('ts', entry)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *arguments):
        return None


if __name__ == '__main__':
    unittest.main()
