"""E11-T09: INC-64 (Caddy picks a club host's SPA from the Core, no CLUB_ADMIN_HOSTS) and INC-37 (the env inventory).

Round 2 (E140): the local proof's evidence helper is never rebound, and the image's Maven step retries a transient 502.
E11-T16: CI runs this file; the Caddy ask leaves without the client's X-Forwarded-Uri and X-Forwarded-Method."""
from pathlib import Path
import ast
import collections
import hashlib
import http.server
import json
import re
import socket
import subprocess
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
# Module constants, so a scratch runner can point one test at another revision of the file (round 2's red runs).
DOCKERFILE = ROOT / 'Dockerfile'
LOCAL_PROOF = ROOT / 'deploy/local-proof.py'
IMAGE = 'caddy:2.10.2-alpine'
PRODUCTION_ENV = {'ACME_EMAIL': 'ops@example.test', 'ID_HOST': 'id.example.test', 'CLUBS_HOST': 'clubs.example.test',
                  'ADMIN_HOST': 'admin.example.test', 'CORE_HOST': 'core.example.test', 'CADDY_ASK_UPSTREAM': 'core:8080',
                  'CADDY_APP_UPSTREAM': 'core:8080'}
# Stands for the Core's GET /internal/domains/app: `office.example.test` is a verified clubs-admin domain. It refuses (403) a
# request that carries a forwarding header, a client credential or another Host, as the Core's internal-network rule would.
STUB = '''
http://:9090 {
    log {
        output stdout
        format json
    }
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
    @client_uri {
        header X-Forwarded-Uri *
    }
    @client_method {
        header X-Forwarded-Method *
    }
    respond @client_uri 403
    respond @client_method 403
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
        port, stub_port = free_port(), free_port()
        with tempfile.TemporaryDirectory(prefix='inc64-caddy-') as directory:
            path = Path(directory)
            for app in ('clubs', 'clubsadmin'):
                (path / app).mkdir()
                (path / app / 'index.html').write_text(f'<h1>AgilityHub {app}</h1>')
            (path / 'Caddyfile').write_text('{\n admin off\n}\n' + snippet + '\nhttp://:8080 {\n import club_spa\n}\n' + STUB)
            container = subprocess.check_output([
                'docker', 'run', '--rm', '-d', '-p', f'127.0.0.1:{port}:8080', '-p', f'127.0.0.1:{stub_port}:9090',
                '-e', 'CADDY_APP_UPSTREAM=127.0.0.1:9090',
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
                # E11-T16: the ask carries neither the client's URI (its query can hold a code) nor its method: the stub refuses both.
                self.assertEqual('<h1>AgilityHub clubsadmin</h1>', get('office.example.test', '/entrar?handoff=fictional-code')[0])
                # Round 2 (review #9): a Host that carries query syntax stays one escaped `host` value, so it cannot name another
                # host to the Core. Go accepts `&` and `=` in a Host header. Unescaped, both would name the back office: the stub
                # answers `clubs-admin` to the raw queries.
                for crafted in ('office.example.test&x=y', 'x&host=office.example.test'):
                    self.assertEqual('<h1>AgilityHub clubs</h1>', get(crafted)[0], crafted)
                    raw = urllib.request.Request(f'http://127.0.0.1:{stub_port}/internal/domains/app?host={crafted}',
                                                 headers={'Host': '127.0.0.1'})
                    with opener.open(raw, timeout=3) as response:
                        self.assertEqual('clubs-admin', response.headers.get('X-Club-App'), crafted)
                logs = subprocess.run(['docker', 'logs', container], check=True, capture_output=True, text=True)
                asked = [json.loads(line)['request']['uri'] for line in (logs.stdout + logs.stderr).splitlines()
                         if line.startswith('{') and '"http.log.access' in line]
                print('CADDY ASKED THE CORE ' + json.dumps([uri for uri in asked if '%26' in uri]), flush=True)
                self.assertIn('/internal/domains/app?host=office.example.test%26x%3Dy', asked)
                self.assertIn('/internal/domains/app?host=x%26host%3Doffice.example.test', asked)
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


def scope_bindings(body):
    """How often each name is bound in one function scope (nested functions, lambdas and comprehensions are their own scopes),
    and which of those names a `def` binds."""
    counts, functions = collections.Counter(), set()

    def visit(node):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            counts[node.name] += 1
            if not isinstance(node, ast.ClassDef):
                functions.add(node.name)
            return
        if isinstance(node, ast.Lambda):
            return
        if isinstance(node, (ast.ListComp, ast.SetComp, ast.DictComp, ast.GeneratorExp)):
            # Only an assignment expression inside a comprehension binds in the enclosing scope.
            counts.update(sub.target.id for sub in ast.walk(node) if isinstance(sub, ast.NamedExpr))
            return
        if isinstance(node, ast.Name) and isinstance(node.ctx, ast.Store):
            counts[node.id] += 1
        elif isinstance(node, (ast.Import, ast.ImportFrom)):
            counts.update((alias.asname or alias.name).split('.')[0] for alias in node.names)
        elif isinstance(node, ast.ExceptHandler) and node.name:
            counts[node.name] += 1
        for child in ast.iter_child_nodes(node):
            visit(child)

    for statement in body:
        visit(statement)
    return counts, functions


class LocalProofTest(unittest.TestCase):
    def test_round2_no_helper_of_the_proof_is_rebound(self):
        # E140: in --security-only mode the JSON-log loop bound `record` to a dict, and the INC-64 step then called
        # `record(verify_command, verified)`: TypeError after every check had passed (CI run 39125075611, amd64 Compose step).
        tree = ast.parse(LOCAL_PROOF.read_text(), str(LOCAL_PROOF))
        scopes = [('<module>', tree.body)] + [(node.name, node.body) for node in ast.walk(tree)
                                               if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))]
        rebound = []
        for name, body in scopes:
            counts, functions = scope_bindings(body)
            rebound += [f'{name}: {function} bound {counts[function]} times' for function in sorted(functions) if counts[function] > 1]
        self.assertEqual([], rebound)
        main = next(body for name, body in scopes if name == 'main')
        self.assertIn('record', scope_bindings(main)[1], 'the evidence helper this test protects is still a def of main()')


class MavenRetryTest(unittest.TestCase):
    """E140: the arm64 image build of bc7b34f6 failed on one `502 Bad Gateway` from Maven Central (run 39129028531). The real
    wrapper, with the Dockerfile's own flags, resolves from a repository whose first answer to every file is a 502."""

    @staticmethod
    def dockerfile_maven_flags():
        lines = DOCKERFILE.read_text().replace('\\\n', ' ').splitlines()
        build = next(line for line in lines if line.startswith('RUN ./mvnw '))
        return [token for token in build.split() if token.startswith('-D')]

    def test_round2_the_image_build_retries_a_transient_502(self):
        parent = (b'<project><modelVersion>4.0.0</modelVersion><groupId>test.e11t09</groupId><artifactId>retry-parent</artifactId>'
                  b'<version>1.0</version><packaging>pom</packaging></project>')
        pom_path = '/test/e11t09/retry-parent/1.0/retry-parent-1.0.pom'
        served = {pom_path: parent, pom_path + '.sha1': hashlib.sha1(parent).hexdigest().encode(),
                  pom_path + '.md5': hashlib.md5(parent).hexdigest().encode()}
        seen = []

        class Repository(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                status = 502 if all(path != self.path for path, _ in seen) else 200 if self.path in served else 404
                seen.append((self.path, status))
                body = served[self.path] if status == 200 else b''
                self.send_response(status)
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *arguments):
                pass

        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Repository)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            with tempfile.TemporaryDirectory(prefix='e11-t09-maven-retry-') as directory:
                work = Path(directory)
                (work / 'settings.xml').write_text('<settings/>')
                (work / 'pom.xml').write_text(
                    '<project><modelVersion>4.0.0</modelVersion><parent><groupId>test.e11t09</groupId><artifactId>retry-parent</artifactId>'
                    '<version>1.0</version><relativePath/></parent><artifactId>retry-child</artifactId><repositories><repository>'
                    f'<id>central</id><url>http://127.0.0.1:{server.server_port}/</url></repository></repositories></project>')
                command = [str(ROOT / 'mvnw'), '-B', '-q', '-s', str(work / 'settings.xml'), f'-Dmaven.repo.local={work / "repository"}',
                           *self.dockerfile_maven_flags(), '-f', str(work / 'pom.xml'), 'validate']
                result = subprocess.run(command, capture_output=True, text=True, timeout=600)
        finally:
            server.shutdown()
            server.server_close()
        print('MAVEN ' + ' '.join(command[1:]) + f' -> exit {result.returncode}; requests {seen}', flush=True)
        self.assertEqual(0, result.returncode, (result.stdout + result.stderr)[-3000:])
        self.assertEqual([(pom_path, 502), (pom_path, 200)], [request for request in seen if request[0] == pom_path])


if __name__ == '__main__':
    unittest.main(verbosity=2)
