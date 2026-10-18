"""E9-T06 (S16 R-16-11): the build session stream's `?access_token=` never reaches a Caddy access log."""
from pathlib import Path
import json
import re
import socket
import subprocess
import tempfile
import time
import unittest
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
IMAGE = 'caddy:2.10.2-alpine'
TOKEN = 'eyJfictional.access.token'


class AccessLogTest(unittest.TestCase):
    def setUp(self):
        self.caddy = (ROOT / 'deploy/Caddyfile').read_text()

    def test_E9_T06_no_site_logs_without_the_filter(self):
        # The only `log` directive is the filtered snippet's; access logging stays off (docs/DEPLOY.md).
        snippet = self.caddy[self.caddy.index('(access_log) {'):self.caddy.index('(api_proxy)')]
        self.assertEqual(1, len(re.findall(r'^\s*log\b', self.caddy, re.M)))
        self.assertRegex(snippet, r'request>uri query \{\s*delete access_token\s*\}')
        self.assertNotIn('import access_log', self.caddy)

    def test_E9_T06_production_caddyfile_still_validates(self):
        env = {'ACME_EMAIL': 'ops@example.test', 'ID_HOST': 'id.example.test', 'CLUBS_HOST': 'clubs.example.test',
               'ADMIN_HOST': 'admin.example.test', 'CORE_HOST': 'core.example.test',
               'CADDY_ASK_UPSTREAM': 'core:8080', 'CADDY_APP_UPSTREAM': 'core:8080'}
        flags = [part for key, value in env.items() for part in ('-e', f'{key}={value}')]
        subprocess.run(['docker', 'run', '--rm', '-v', f'{ROOT / "deploy/Caddyfile"}:/etc/caddy/Caddyfile:ro', *flags, IMAGE,
                        'caddy', 'validate', '--config', '/etc/caddy/Caddyfile'], check=True, capture_output=True)

    def test_E9_T06_real_caddy_drops_the_query_token(self):
        # The real snippet in the production image, in front of a fixed answer, as a site would import it.
        snippet = self.caddy[self.caddy.index('(access_log) {'):self.caddy.index('(api_proxy)')]
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0))
            port = sock.getsockname()[1]
        with tempfile.TemporaryDirectory(prefix='e9t06-caddy-') as directory:
            path = Path(directory) / 'Caddyfile'
            path.write_text('{\n admin off\n}\n' + snippet + '\nhttp://:8080 {\n import access_log\n respond "fictional stream" 200\n}\n')
            subprocess.run(['docker', 'run', '--rm', '-v', f'{path}:/etc/caddy/Caddyfile:ro', IMAGE,
                            'caddy', 'validate', '--config', '/etc/caddy/Caddyfile'], check=True, capture_output=True)
            container = subprocess.check_output([
                'docker', 'run', '--rm', '-d', '-p', f'127.0.0.1:{port}:8080',
                '-v', f'{path}:/etc/caddy/Caddyfile:ro', IMAGE], text=True).strip()
            try:
                opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
                url = f'http://127.0.0.1:{port}/api/v1/build-sessions/session-a/events?access_token={TOKEN}&lastEventId=3'
                for attempt in range(50):
                    try:
                        with opener.open(url, timeout=1) as response:
                            self.assertEqual(b'fictional stream', response.read())
                        break
                    except OSError:
                        time.sleep(0.1)
                else:
                    self.fail('Caddy did not answer')
                logs = ''
                for attempt in range(50):
                    logs = subprocess.run(['docker', 'logs', container], capture_output=True, text=True).stderr
                    if '"uri"' in logs:
                        break
                    time.sleep(0.1)
                access = [json.loads(line) for line in logs.splitlines() if line.startswith('{') and '"http.log.access' in line]
                self.assertTrue(access, logs)
                self.assertEqual('/api/v1/build-sessions/session-a/events?lastEventId=3', access[-1]['request']['uri'])
                self.assertNotIn(TOKEN, logs)
                self.assertNotIn('access_token', logs)
            finally:
                subprocess.run(['docker', 'rm', '-f', container], check=True, capture_output=True)


if __name__ == '__main__':
    unittest.main()
