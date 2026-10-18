"""E11-T09: INC-64 (Caddy picks a club host's SPA from the Core, no CLUB_ADMIN_HOSTS) and INC-37 (the env inventory)."""
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
PRODUCTION_ENV = {'ACME_EMAIL': 'ops@example.test', 'ID_HOST': 'id.example.test', 'CLUBS_HOST': 'clubs.example.test',
                  'ADMIN_HOST': 'admin.example.test', 'CORE_HOST': 'core.example.test', 'CADDY_ASK_UPSTREAM': 'core:8080',
                  'CADDY_APP_UPSTREAM': 'core:8080'}
# Stands for the Core's GET /internal/domains/app: `office.example.test` is a verified clubs-admin domain. It refuses (403) a
# request that carries a forwarding header, a client credential or another Host, as the Core's internal-network rule would.
STUB = '''
http://:9090 {
    @leak {
        header X-Forwarded-For *
    }
    @credentials {
        header Authorization *
    }
    @cookie {
        header Cookie *
    }
    @foreign {
        not host 127.0.0.1
    }
    respond @leak 403
    respond @credentials 403
    respond @cookie 403
    respond @foreign 403
    @admin {
        path /internal/domains/app
        query host=office.example.test
    }
    header @admin X-Club-App clubs-admin
    @members {
        path /internal/domains/app
        not query host=office.example.test
    }
    header @members X-Club-App clubs
    respond 200
}
'''


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


class ClubAppTest(unittest.TestCase):
    def setUp(self):
        self.caddy = (ROOT / 'deploy/Caddyfile').read_text()

    def test_INC_64_no_static_admin_host_list_is_left(self):
        for file in ('deploy/Caddyfile', 'deploy/compose.prod.yaml', 'deploy/compose.prod.local.yaml', 'deploy/.env.prod.example'):
            self.assertNotIn('CLUB_ADMIN_HOSTS', (ROOT / file).read_text(), file)
        catch_all = self.caddy[self.caddy.index('https:// {'):]
        self.assertIn('import club_spa', catch_all)
        self.assertNotIn('@club_admin host', self.caddy)
        for compose in ('deploy/compose.prod.yaml', 'deploy/compose.prod.local.yaml'):
            self.assertRegex((ROOT / compose).read_text(), r'CADDY_APP_UPSTREAM: core:8080', compose)

    def test_INC_64_the_internal_routes_never_leave_through_the_public_sites(self):
        # The Core's own host refuses /internal/*; the SPA sites proxy only /api/*, /oauth2/*, /.well-known/* and /connect/logout.
        core = self.caddy[self.caddy.index('{$CORE_HOST} {'):self.caddy.index('# Never published by Compose.')]
        self.assertRegex(core, r'@private path /internal/\* /actuator/\*\s*respond @private 404')
        backend = re.search(r'@backend path ([^\n]+)', self.caddy).group(1).split()
        self.assertFalse([path for path in backend if path.startswith('/internal')])

    def test_INC_64_production_caddyfile_validates(self):
        flags = [part for key, value in PRODUCTION_ENV.items() for part in ('-e', f'{key}={value}')]
        subprocess.run(['docker', 'run', '--rm', '-v', f'{ROOT / "deploy/Caddyfile"}:/etc/caddy/Caddyfile:ro', *flags, IMAGE,
                        'caddy', 'validate', '--config', '/etc/caddy/Caddyfile'], check=True, capture_output=True)

    def test_INC_64_real_caddy_serves_each_host_the_app_the_core_names(self):
        snippet = self.caddy[self.caddy.index('(club_spa) {'):self.caddy.index('https:// {')]
        port = free_port()
        with tempfile.TemporaryDirectory(prefix='inc64-caddy-') as directory:
            path = Path(directory)
            for app in ('clubs', 'clubsadmin'):
                (path / app).mkdir()
                (path / app / 'index.html').write_text(f'<h1>AgilityHub {app}</h1>')
            (path / 'Caddyfile').write_text('{\n admin off\n}\n' + snippet + '\nhttp://:8080 {\n import club_spa\n}\n' + STUB)
            container = subprocess.check_output([
                'docker', 'run', '--rm', '-d', '-p', f'127.0.0.1:{port}:8080', '-e', 'CADDY_APP_UPSTREAM=127.0.0.1:9090',
                '-v', f'{path / "Caddyfile"}:/etc/caddy/Caddyfile:ro', '-v', f'{path / "clubs"}:/srv/clubs:ro',
                '-v', f'{path / "clubsadmin"}:/srv/clubsadmin:ro', IMAGE], text=True).strip()
            try:
                opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

                def get(host, route='/', headers=()):
                    request = urllib.request.Request(f'http://127.0.0.1:{port}{route}', headers={'Host': host, **dict(headers)})
                    with opener.open(request, timeout=3) as response:
                        return response.read().decode(), response.headers

                for attempt in range(50):
                    try:
                        get('members.example.test')
                        break
                    except OSError:
                        time.sleep(0.1)
                for route in ('/', '/a/deep/link', '/index.html'):
                    body, headers = get('office.example.test', route)
                    self.assertEqual('<h1>AgilityHub clubsadmin</h1>', body, route)
                    self.assertEqual('no-cache', headers.get('Cache-Control'))
                    self.assertIsNone(headers.get('X-Club-App'))
                    self.assertEqual('<h1>AgilityHub clubs</h1>', get('members.example.test', route)[0], route)
                # A client cannot pick the back office itself, and its credentials and forwarding headers never reach the Core.
                self.assertEqual('<h1>AgilityHub clubs</h1>', get('members.example.test', '/', [('X-Club-App', 'clubs-admin')])[0])
                leaky = [('Authorization', 'Bearer eyJfictional'), ('Cookie', 'ah_refresh=fictional'), ('X-Forwarded-For', '198.51.100.7')]
                self.assertEqual('<h1>AgilityHub clubsadmin</h1>', get('office.example.test', '/', leaky)[0])
            finally:
                subprocess.run(['docker', 'rm', '-f', container], check=True, capture_output=True)


class InventoryTest(unittest.TestCase):
    def test_INC_37_every_production_variable_is_in_the_deploy_inventory(self):
        # INC-37 (26-09 review): OIDC_LEARN_CLIENT_SECRET, MAIL_FROM_PLATFORM and SENDGRID_WEBHOOK_PUBLIC_KEY were missing; E11-T04
        # added them. Every interpolated variable of the production examples stays in the inventory.
        doc = (ROOT / 'docs/DEPLOY.md').read_text()
        inventory = doc[doc.index('### Environment inventory'):]
        names = set(re.findall(r'\$\{([A-Z][A-Z0-9_]*)', (ROOT / 'deploy/compose.prod.yaml').read_text()))
        names |= {line.split('=', 1)[0] for line in (ROOT / 'deploy/.env.prod.example').read_text().splitlines()
                  if re.match(r'[A-Z][A-Z0-9_]*=', line)}
        missing = sorted(name for name in names if f'`{name}`' not in inventory)
        self.assertEqual([], missing)
        for name in ('OIDC_LEARN_CLIENT_SECRET', 'MAIL_FROM_PLATFORM', 'SENDGRID_WEBHOOK_PUBLIC_KEY'):
            self.assertIn(name, names)


if __name__ == '__main__':
    unittest.main(verbosity=2)
