"""F08: exercise the real Caddy image against dead, failed and recovered fictional upstreams.

CFG-05: the same fixture checks how both SPA snippets deliver static files. CFG-04: and which Content-Security-Policy they send."""
import gzip
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import unittest
import urllib.error
import urllib.request

SOURCE = Path(os.environ.get('CADDYFILE', Path(__file__).with_name('Caddyfile')))
IMAGE = 'caddy:2.10.2-alpine'
# Above Caddy's 512-byte minimum for compression.
APP_JS = 'console.log("fictional");\n' * 200
# CFG-04: each fictional bundle's policy, as its build writes it (one line, no newline), and the Core's own for API answers.
POLICIES = {app: f"default-src 'self'; script-src 'self' 'sha256-fictional-{app}'; object-src 'none'; frame-ancestors 'none'"
            for app in ('id', 'clubs', 'clubsadmin')}
API_POLICY = "default-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'"
# CFG-05: the robots.txt every bundle's build writes.
ROBOTS_TXT = 'User-agent: *\nDisallow: /\n'

def port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]

class GatewayUnavailableTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = SOURCE.read_text()
        snippets = source[source.index('(security_headers)'):source.index('{$ID_HOST}')]
        club = source[source.index('(club_spa) {'):source.index('https:// {')]
        dead = club.replace('(club_spa)', '(club_spa_dead)').replace('{$CADDY_APP_UPSTREAM}', '127.0.0.1:59999')
        cls.directory = tempfile.TemporaryDirectory(prefix='gateway-f08-')
        root = Path(cls.directory.name)
        (root / 'maintenance').mkdir()
        cls.maintenance_flag = root / 'maintenance/enabled'
        for app in ('clubs', 'clubsadmin', 'id'):
            (root / app).mkdir()
            (root / app / 'index.html').write_text('<h1>Fictional ' + app + '</h1>')
            (root / app / 'assets').mkdir()
            (root / app / 'assets/app.css').write_text('body{color:navy}')
            (root / app / 'assets/app.js').write_text(APP_JS)
            (root / app / 'content-security-policy.txt').write_text(POLICIES[app])
            (root / app / 'robots.txt').write_text(ROBOTS_TXT)
        conf = '''{ admin off }
''' + snippets.replace('core:8080', '127.0.0.1:9090') + club + dead + '''
http://:8080 {
    import gateway_unavailable
    import security_headers
    import api_proxy
    handle { import club_spa }
}
http://:8081 {
    import gateway_unavailable
    import security_headers
    @api path /api/* /oauth2/*
    handle @api { reverse_proxy 127.0.0.1:59999 }
    handle { import club_spa_dead }
}
http://:8082 {
    import spa /srv/clubs
}
http://:8083 {
    import spa /srv/id
}
http://:9090 {
    @unavailable query host=unavailable.example.test
    respond @unavailable 502
    @refused query host=unknown.example.test
    respond @refused 404
    @office query host=office.example.test
    header @office X-Club-App clubs-admin
    @member query host=members.example.test
    header @member X-Club-App clubs
    @api path /api/*
    handle @api {
        header Content-Type application/json
        header Content-Security-Policy "API_POLICY"
        respond `{"code":"INTERNAL_ERROR","message":"Fictional application error","details":{},"traceId":"original-trace"}` 500
    }
    respond 200
}
'''
        conf = conf.replace('API_POLICY', API_POLICY)
        # Caddyfile blocks must have their directives on separate lines.
        conf = conf.replace('{ admin off }', '{\n admin off\n}').replace('handle { import club_spa }', 'handle {\n import club_spa\n}').replace('handle { import club_spa_dead }', 'handle {\n import club_spa_dead\n}').replace('handle @api { reverse_proxy 127.0.0.1:59999 }', 'handle @api {\n reverse_proxy 127.0.0.1:59999\n}')
        (root / 'Caddyfile').write_text(conf)
        cls.root = root
        cls.normal, cls.dead, cls.product, cls.identity = port(), port(), port(), port()
        cls.container = subprocess.check_output(['docker','run','--rm','-d',
            '-p',f'127.0.0.1:{cls.normal}:8080','-p',f'127.0.0.1:{cls.dead}:8081',
            '-p',f'127.0.0.1:{cls.product}:8082','-p',f'127.0.0.1:{cls.identity}:8083',
            '-e','CADDY_APP_UPSTREAM=127.0.0.1:9090',
            '-v',f'{root / "Caddyfile"}:/etc/caddy/Caddyfile:ro',
            '-v',f'{root / "maintenance"}:/maintenance:ro',
            '-v',f'{root / "clubs"}:/srv/clubs:ro','-v',f'{root / "clubsadmin"}:/srv/clubsadmin:ro',
            '-v',f'{root / "id"}:/srv/id:ro',IMAGE],text=True).strip()
        cls.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        for _ in range(50):
            try:
                if cls.request(cls.normal, 'members.example.test')[0] == 200:
                    return
            except OSError:
                time.sleep(.1)
        logs = subprocess.run(['docker','logs',cls.container],capture_output=True,text=True)
        cls.tearDownClass()
        raise RuntimeError('Fictional Caddy fixture did not start: ' + logs.stderr)

    @classmethod
    def tearDownClass(cls):
        subprocess.run(['docker','rm','-f',cls.container],capture_output=True,check=True)
        cls.directory.cleanup()

    @classmethod
    def request(cls, endpoint, host, path='/agenda', accept='text/html', method='GET'):
        req = urllib.request.Request(f'http://127.0.0.1:{endpoint}{path}',method=method,
              headers={'Host':host,'Accept':accept,'X-Club-App':'clubs-admin'})
        try:
            response = cls.opener.open(req,timeout=3)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.status,response.headers,response.read().decode()

    def check_unavailable(self, result, kind):
        status,headers,body=result
        self.assertEqual(503,status)
        self.assertTrue(headers['Content-Type'].startswith(kind))
        self.assertEqual('no-store',headers['Cache-Control'])
        self.assertEqual('30',headers['Retry-After'])
        self.assertEqual('nosniff',headers['X-Content-Type-Options'])
        self.assertNotIn('Content-Disposition',headers)
        self.assertNotIn('core:8080',body)
        self.assertNotIn('fictional-capability',body)
        return body

    def test_F08_dead_backend_navigation_has_readable_page(self):
        body=self.check_unavailable(self.request(self.dead,'members.example.test','/agenda?token=fictional-capability'),'text/html')
        self.assertIn('Servei temporalment no disponible',body)
        self.assertIn('href=""',body)
        self.assertNotIn('{err.id}',body)

    def test_F08_forward_auth_502_is_not_an_empty_download(self):
        body=self.check_unavailable(self.request(self.normal,'unavailable.example.test'),'text/html')
        self.assertIn('<html',body)

    def test_F08_api_failure_remains_json_even_with_browser_accept(self):
        for path in ('/api/v1/me','/oauth2/token'):
            with self.subTest(path=path):
                body=self.check_unavailable(self.request(self.dead,'members.example.test',path),'application/json')
                payload=json.loads(body)
                self.assertEqual('INTERNAL_ERROR',payload['code'])
                self.assertTrue(payload['traceId'])

    def test_F08_non_html_and_non_get_requests_get_json(self):
        for accept,method in [('application/json','GET'),('text/html','POST')]:
            with self.subTest(accept=accept,method=method):
                self.check_unavailable(self.request(self.dead,'members.example.test',accept=accept,method=method),'application/json')

    def test_F08_healthy_domain_keeps_its_own_spa_and_denied_stays_denied(self):
        for host,app in [('members.example.test','clubs'),('office.example.test','clubsadmin')]:
            with self.subTest(host=host):
                status,headers,body=self.request(self.normal,host)
                self.assertEqual(200,status)
                self.assertEqual('<h1>Fictional '+app+'</h1>',body)
                self.assertEqual('no-cache',headers['Cache-Control'])
        self.assertEqual(404,self.request(self.normal,'unknown.example.test')[0])

    def test_F08_real_api_error_is_preserved_not_replaced(self):
        status,headers,body=self.request(self.normal,'members.example.test','/api/v1/me')
        self.assertEqual(500,status)
        self.assertEqual('original-trace',json.loads(body)['traceId'])
        self.assertNotIn('Retry-After',headers)

    def test_F08_planned_maintenance_does_not_depend_on_core_or_spa_assets(self):
        for endpoint in (self.product, self.normal, self.dead):
            with self.subTest(endpoint=endpoint):
                self.maintenance_flag.write_text('enabled')
                try:
                    body = self.check_unavailable(self.request(endpoint, 'members.example.test', '/agenda?token=fictional-capability'), 'text/html')
                    self.assertIn('Actualització en curs', body)
                    self.assertIn('<style>', body)
                    self.assertNotIn('<link', body)
                    self.check_unavailable(self.request(endpoint, 'members.example.test', '/api/v1/me'), 'application/json')
                    self.check_unavailable(self.request(endpoint, 'members.example.test', '/assets/app.css', 'text/css'), 'application/json')
                finally:
                    self.maintenance_flag.unlink()
        self.assertEqual(200, self.request(self.normal, 'members.example.test')[0])
        self.assertEqual(200, self.request(self.product, 'members.example.test')[0])

    def test_F08_missing_versioned_assets_never_receive_the_spa_html(self):
        for endpoint in (self.normal, self.product):
            with self.subTest(endpoint=endpoint):
                self.assertEqual('body{color:navy}', self.request(endpoint, 'members.example.test', '/assets/app.css', 'text/css')[2])
                status, _, body = self.request(endpoint, 'members.example.test', '/assets/old.css', 'text/css')
                self.assertEqual(404, status)
                self.assertNotIn('Fictional clubs', body)

    def test_CFG_05_static_files_are_compressed_cached_by_kind_and_never_answered_with_the_shell(self):
        for endpoint in (self.normal, self.product):
            with self.subTest(endpoint=endpoint):
                # An existing file under /assets/ (content-hashed) is cached for a year; everything else revalidates.
                status, headers, _ = self.request(endpoint, 'members.example.test', '/assets/app.css', 'text/css')
                self.assertEqual((200, 'public, max-age=31536000, immutable'), (status, headers['Cache-Control']))
                for path in ('/', '/agenda', '/index.html'):
                    status, headers, body = self.request(endpoint, 'members.example.test', path)
                    self.assertEqual((200, 'no-cache', '<h1>Fictional clubs</h1>'), (status, headers['Cache-Control'], body), path)
                status, headers, _ = self.request(endpoint, 'members.example.test', '/assets/old.css', 'text/css')
                self.assertEqual((404, 'no-cache'), (status, headers['Cache-Control']))
                # A dot path or a missing file is a 404, never the SPA shell with 200. (CFG-05 pass 2: every real bundle now
                # carries robots.txt, so the missing text file of this list is another one; robots.txt is checked below.)
                for path in ('/.env', '/.git/config', '/assets/.hidden', '/humans.txt', '/sitemap.xml', '/app.js.map', '/wp-login.php'):
                    status, _, body = self.request(endpoint, 'members.example.test', path, '*/*')
                    self.assertEqual(404, status, path)
                    self.assertNotIn('Fictional', body, path)
                request = urllib.request.Request(f'http://127.0.0.1:{endpoint}/assets/app.js',
                                                 headers={'Host': 'members.example.test', 'Accept-Encoding': 'gzip'})
                with self.opener.open(request, timeout=3) as response:
                    self.assertEqual('gzip', response.headers['Content-Encoding'])
                    self.assertEqual(APP_JS, gzip.decompress(response.read()).decode())

    def test_CFG_05_every_spa_host_serves_its_bundles_robots_txt(self):
        # Owner decision of 13 Nov 2026: `Disallow: /` on every host. The product hosts, the identity host and the club aliases
        # (both apps) each serve the robots.txt of their bundle, never the SPA shell.
        for endpoint, host in ((self.product, 'clubs.example.test'), (self.identity, 'id.example.test'),
                               (self.normal, 'members.example.test'), (self.normal, 'office.example.test')):
            with self.subTest(host=host):
                status, headers, body = self.request(endpoint, host, '/robots.txt', 'text/plain')
                self.assertEqual((200, ROBOTS_TXT), (status, body))
                self.assertTrue(headers['Content-Type'].startswith('text/plain'))
                self.assertEqual('no-cache', headers['Cache-Control'])

    def test_CFG_04_every_static_answer_carries_its_bundles_policy_and_the_api_keeps_its_own(self):
        # The product sites (`spa`) and the club catch-all (`club_spa`, the root the Core names) send the served root's policy.
        for endpoint, host, app in ((self.product, 'clubs.example.test', 'clubs'), (self.identity, 'id.example.test', 'id'),
                                    (self.normal, 'members.example.test', 'clubs'), (self.normal, 'office.example.test', 'clubsadmin')):
            for path, accept, status in (('/', 'text/html', 200), ('/agenda', 'text/html', 200), ('/index.html', 'text/html', 200),
                                         ('/assets/app.css', 'text/css', 200), ('/assets/old.css', 'text/css', 404)):
                with self.subTest(host=host, endpoint=endpoint, path=path):
                    answer, headers, _ = self.request(endpoint, host, path, accept)
                    self.assertEqual((status, POLICIES[app]), (answer, headers['Content-Security-Policy']))
                    self.assertEqual(1, len(headers.get_all('Content-Security-Policy')))
        # A proxied API answer keeps the Core's own policy and never gets a bundle's.
        status, headers, _ = self.request(self.normal, 'members.example.test', '/api/v1/me', 'application/json')
        self.assertEqual((500, [API_POLICY]), (status, headers.get_all('Content-Security-Policy')))
        # A new bundle's policy applies at once: the file is read per request, with no reload.
        policy = self.root / 'clubsadmin/content-security-policy.txt'
        try:
            policy.write_text(POLICIES['clubsadmin'].replace('fictional-clubsadmin', 'fictional-next-build'))
            headers = self.request(self.normal, 'office.example.test')[1]
            self.assertIn("'sha256-fictional-next-build'", headers['Content-Security-Policy'])
        finally:
            policy.write_text(POLICIES['clubsadmin'])

if __name__ == '__main__':
    unittest.main(verbosity=2)
