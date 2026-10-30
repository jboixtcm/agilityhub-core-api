"""F08: exercise the real Caddy image against dead, failed and recovered fictional upstreams."""
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
        for app in ('clubs', 'clubsadmin'):
            (root / app).mkdir()
            (root / app / 'index.html').write_text('<h1>Fictional ' + app + '</h1>')
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
        respond `{"code":"INTERNAL_ERROR","message":"Fictional application error","details":{},"traceId":"original-trace"}` 500
    }
    respond 200
}
'''
        # Caddyfile blocks must have their directives on separate lines.
        conf = conf.replace('{ admin off }', '{\n admin off\n}').replace('handle { import club_spa }', 'handle {\n import club_spa\n}').replace('handle { import club_spa_dead }', 'handle {\n import club_spa_dead\n}').replace('handle @api { reverse_proxy 127.0.0.1:59999 }', 'handle @api {\n reverse_proxy 127.0.0.1:59999\n}')
        (root / 'Caddyfile').write_text(conf)
        cls.normal, cls.dead = port(), port()
        cls.container = subprocess.check_output(['docker','run','--rm','-d',
            '-p',f'127.0.0.1:{cls.normal}:8080','-p',f'127.0.0.1:{cls.dead}:8081',
            '-e','CADDY_APP_UPSTREAM=127.0.0.1:9090',
            '-v',f'{root / "Caddyfile"}:/etc/caddy/Caddyfile:ro',
            '-v',f'{root / "clubs"}:/srv/clubs:ro','-v',f'{root / "clubsadmin"}:/srv/clubsadmin:ro',IMAGE],text=True).strip()
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

if __name__ == '__main__':
    unittest.main(verbosity=2)
