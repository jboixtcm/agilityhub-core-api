"""E11-T09: INC-64 (Caddy picks a club host's SPA from the Core, no CLUB_ADMIN_HOSTS) and INC-37 (the env inventory).

Round 2 (E140): the local proof's evidence helper is never rebound, and the image's Maven step retries a transient 502.
E11-T16: CI runs this file; the Caddy ask leaves without the client's X-Forwarded-Uri and X-Forwarded-Method."""
from pathlib import Path
import ast
import collections
import hashlib
import http.server
import http.client
import io
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
IMAGE = 'caddy:2.11.9-alpine'
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

    def test_CFG_03_the_core_host_caps_webhook_bodies_before_the_proxy(self):
        # Only the Core's own host proxies /webhooks/*; Caddy stops a body above the 1 MiB the Core reads (the Core refuses it too).
        core = self.caddy[self.caddy.index('{$CORE_HOST} {'):self.caddy.index('# Never published by Compose.')]
        self.assertRegex(core, r'request_body /webhooks/\* \{\s*max_size 1MiB\s*\}\s*reverse_proxy core:8080')
        backend = re.search(r'@backend path ([^\n]+)', self.caddy).group(1).split()
        self.assertFalse([path for path in backend if path.startswith('/webhooks')])

    def test_CFG_03_every_site_that_proxies_the_api_caps_its_bodies_at_two_megabytes_but_the_signed_uploads(self):
        # Owner decision of 13 Nov 2026: 2 MB for /api/* and /oauth2/*, exempting only the two signed upload PUTs (files.maxSizeMb).
        snippet = self.caddy[self.caddy.index('(api_body_limit) {'):self.caddy.index('# CFG-05: the static answers')]
        self.assertRegex(snippet, r'@api_body \{\s*path /api/\* /oauth2/\*\s*not \{\s*method PUT\s*'
                                  r'path /api/v1/attachments/uploads/\* /api/v1/signup/uploads\s*\}\s*\}')
        self.assertRegex(snippet, r'request_body @api_body \{\s*max_size 2MB\s*\}')
        self.assertRegex(snippet, r'handle_errors 413 \{[^`]*respond `\{"code":"VALIDATION_ERROR",[^`]*"traceId":"\{err\.id\}"\}` 400')
        # The only request_body directives: this one and the Core host's webhook cap.
        self.assertEqual(['@api_body', '/webhooks/*'], re.findall(r'^\s*request_body (\S+)', self.caddy, re.M))
        # Every site that proxies /api/* or /oauth2/* imports it: the three product SPAs (through `spa`), the club catch-all and
        # the Core's own host.
        spa = self.caddy[self.caddy.index('(spa) {'):self.caddy.index('{$ID_HOST} {')]
        catch_all = self.caddy[self.caddy.index('https:// {'):]
        core = self.caddy[self.caddy.index('{$CORE_HOST} {'):self.caddy.index('# Never published by Compose.')]
        for name, block in (('spa', spa), ('https://', catch_all), ('{$CORE_HOST}', core)):
            self.assertIn('import api_body_limit', block, name)
        for host in ('{$ID_HOST}', '{$CLUBS_HOST}', '{$ADMIN_HOST}'):
            self.assertRegex(self.caddy, re.escape(host) + r' \{\s*import spa /srv/')
        sites_with_api = [block.split(' ', 1)[0] for block in re.split(r'\n(?=\S)', self.caddy)
                          if re.match(r'(https?://|\{\$)', block) and ('import api_proxy' in block or 'reverse_proxy core:8080' in block)]
        self.assertEqual(['{$CORE_HOST}', 'https://'], sites_with_api)

    def test_CFG_05_the_core_host_answers_robots_txt_itself_and_proxies_the_rest(self):
        # Owner decision of 13 Nov 2026: `Disallow: /` on every host. The Core's host serves no bundle, so Caddy answers it.
        core = self.caddy[self.caddy.index('{$CORE_HOST} {'):self.caddy.index('# Never published by Compose.')]
        port = free_port()
        core = core.replace('{$CORE_HOST} {', 'http://:8080 {').replace('reverse_proxy core:8080', 'reverse_proxy 127.0.0.1:9091')
        snippets = self.caddy[self.caddy.index('(access_log) {'):self.caddy.index('(spa) {')]
        stub = 'http://:9091 {\n respond "fictional core" 200\n}\n'
        with tempfile.TemporaryDirectory(prefix='cfg05-caddy-') as directory:
            path = Path(directory) / 'Caddyfile'
            path.write_text('{\n admin off\n}\n' + snippets + core + stub)
            container = subprocess.check_output(['docker', 'run', '--rm', '-d', '-p', f'127.0.0.1:{port}:8080',
                                                 '-v', f'{path}:/etc/caddy/Caddyfile:ro', IMAGE], text=True).strip()
            try:
                opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

                def get(route):
                    request = urllib.request.Request(f'http://127.0.0.1:{port}{route}', headers={'Host': 'core.example.test'})
                    with opener.open(request, timeout=3) as response:
                        return response.read().decode(), response.headers

                for attempt in range(50):
                    try:
                        get('/api/v1/health')
                        break
                    except OSError:
                        time.sleep(0.1)
                for route in ('/robots.txt', '/robots.txt?from=crawler'):
                    body, headers = get(route)
                    self.assertEqual('User-agent: *\nDisallow: /\n', body, route)
                    self.assertEqual('text/plain; charset=utf-8', headers['Content-Type'])
                self.assertEqual('fictional core', get('/api/v1/health')[0])
                self.assertEqual('fictional core', get('/robots.txt.bak')[0])
            finally:
                subprocess.run(['docker', 'rm', '-f', container], check=True, capture_output=True)

    def test_INC_64_production_caddyfile_validates(self):
        flags = [part for key, value in PRODUCTION_ENV.items() for part in ('-e', f'{key}={value}')]
        subprocess.run(['docker', 'run', '--rm', '-v', f'{ROOT / "deploy/Caddyfile"}:/etc/caddy/Caddyfile:ro', *flags, IMAGE,
                        'caddy', 'validate', '--config', '/etc/caddy/Caddyfile'], check=True, capture_output=True)

    def test_INC_64_real_caddy_serves_each_host_the_app_the_core_names(self):
        # CFG-05: club_spa serves its files through the shared static_files snippet.
        snippet = (self.caddy[self.caddy.index('(static_files) {'):self.caddy.index('(spa) {')]
                   + self.caddy[self.caddy.index('(club_spa) {'):self.caddy.index('https:// {')])
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


class BodyLimitTest(unittest.TestCase):
    """CFG-03 (owner decision of 13 Nov 2026): the production snippets in the real Caddy image, in front of a fictional upstream
    that reads every body to its end and records how many bytes reached it."""

    def test_CFG_03_real_caddy_stops_api_bodies_above_two_megabytes_and_lets_the_signed_uploads_through(self):
        caddy = (ROOT / 'deploy/Caddyfile').read_text()
        received = {}

        class Upstream(http.server.BaseHTTPRequestHandler):
            protocol_version = 'HTTP/1.1'

            def handle_body(self):
                count = 0
                try:
                    if self.headers.get('Transfer-Encoding', '').lower() == 'chunked':
                        while (size := int(self.rfile.readline().split(b';')[0].strip() or b'0', 16)) > 0:
                            count += len(self.rfile.read(size))
                            self.rfile.readline()
                        self.rfile.readline()
                    else:
                        length = int(self.headers.get('Content-Length') or 0)
                        while count < length and (chunk := self.rfile.read(min(65536, length - count))):
                            count += len(chunk)
                    received[self.command + ' ' + self.path] = count
                    body = json.dumps({'bytes': count}).encode()
                    self.send_response(200)
                    self.send_header('Content-Type', 'application/json')
                    self.send_header('Content-Length', str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                except (OSError, ValueError):
                    # The proxy gave up on the body: the request never reached its end.
                    received[self.command + ' ' + self.path] = count
                    self.close_connection = True

            do_POST = do_PUT = do_PATCH = handle_body

            def log_message(self, *arguments):
                pass

        upstream = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Upstream)
        threading.Thread(target=upstream.serve_forever, daemon=True).start()
        spa_port, core_port = free_port(), free_port()
        target = f'127.0.0.1:{upstream.server_port}'
        snippets = caddy[caddy.index('(security_headers)'):caddy.index('{$ID_HOST}')].replace('core:8080', target)
        core = caddy[caddy.index('{$CORE_HOST} {'):caddy.index('# Never published by Compose.')]
        core = core.replace('{$CORE_HOST} {', f'http://:{core_port} {{').replace('core:8080', target)
        try:
            with tempfile.TemporaryDirectory(prefix='cfg03-caddy-') as directory:
                path = Path(directory) / 'Caddyfile'
                path.write_text('{\n admin off\n}\n' + snippets + f'\nhttp://:{spa_port} {{\n import spa /srv/clubs\n}}\n' + core)
                # Host networking: Caddy reaches the upstream on the host's loopback, as the Core is reached on the compose network.
                container = subprocess.check_output(['docker', 'run', '--rm', '-d', '--network', 'host',
                                                     '-v', f'{path}:/etc/caddy/Caddyfile:ro', IMAGE], text=True).strip()
                try:
                    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

                    def send(port, method, route, size, chunked=False):
                        if size > 1:
                            # urllib finishes sendall before reading the response. An early Caddy rejection can
                            # reset that write and hide its valid error response. curl reads while uploading.
                            request_file = Path(directory) / 'request.bin'
                            headers_file = Path(directory) / 'response.headers'
                            body_file = Path(directory) / 'response.body'
                            request_file.write_bytes(b'0' * size)
                            args = ['curl', '--noproxy', '*', '--http1.1', '--silent', '--show-error', '--max-time', '30',
                                    '--request', method, '--header', 'Content-Type: application/json',
                                    '--header', 'Expect: 100-continue', '--data-binary', '@' + str(request_file),
                                    '--dump-header', str(headers_file), '--output', str(body_file)]
                            if chunked:
                                args += ['--header', 'Transfer-Encoding: chunked']
                            subprocess.run(args + [f'http://127.0.0.1:{port}{route}'], check=True, capture_output=True, timeout=35)
                            responses = [block for block in headers_file.read_bytes().split(b'\r\n\r\n') if block.startswith(b'HTTP/')]
                            status_line, raw_headers = responses[-1].split(b'\r\n', 1)
                            status = int(status_line.split()[1])
                            headers = http.client.parse_headers(io.BytesIO(raw_headers + b'\r\n\r\n'))
                            return status, headers, body_file.read_text()
                        data = iter([b'0' * size]) if chunked else b'0' * size
                        headers = {'Content-Type': 'application/json'}
                        request = urllib.request.Request(f'http://127.0.0.1:{port}{route}', data=data, method=method, headers=headers)
                        try:
                            response = opener.open(request, timeout=30)
                        except urllib.error.HTTPError as error:
                            response = error
                        with response:
                            return response.status, response.headers, response.read().decode()

                    for attempt in range(50):
                        try:
                            send(spa_port, 'POST', '/api/v1/ready', 1)
                            break
                        except OSError:
                            time.sleep(0.1)
                    else:
                        self.fail('Caddy did not answer: ' + subprocess.run(['docker', 'logs', container], capture_output=True,
                                                                             text=True).stderr[-2000:])

                    def refused(port, method, route, size, chunked=False):
                        status, headers, body = send(port, method, route, size, chunked)
                        self.assertEqual(400, status, (method, route, size, body))
                        self.assertTrue(headers['Content-Type'].startswith('application/json'), route)
                        self.assertEqual('no-store', headers['Cache-Control'])
                        error = json.loads(body)
                        self.assertEqual(['code', 'details', 'message', 'traceId'], sorted(error))
                        self.assertEqual(('VALIDATION_ERROR', {}), (error['code'], error['details']))
                        self.assertTrue(error['traceId'])
                        # The upstream never got the whole body (if the proxy started streaming it at all).
                        time.sleep(0.2)
                        self.assertLess(received.get(method + ' ' + route, 0), size, route)

                    def accepted(port, method, route, size, chunked=False):
                        status, _, body = send(port, method, route, size, chunked)
                        self.assertEqual((200, {'bytes': size}), (status, json.loads(body)), (method, route))

                    for port in (spa_port, core_port):
                        with self.subTest(site='spa' if port == spa_port else 'core'):
                            # 2 MB is 2,000,000 bytes: a body of exactly that size passes, one byte more does not.
                            accepted(port, 'POST', '/api/v1/class-sessions', 2_000_000)
                            refused(port, 'POST', '/api/v1/members', 2_000_001)
                            refused(port, 'PATCH', '/api/v1/members/m-1', 3_000_000, chunked=True)
                            refused(port, 'POST', '/oauth2/token', 2_000_001)
                            refused(port, 'POST', '/oauth2/session', 2_500_000, chunked=True)
                            # The signed upload PUTs are exempt: the Core bounds them by files.maxSizeMb while it streams them.
                            accepted(port, 'PUT', '/api/v1/attachments/uploads/u-1', 3_000_000)
                            accepted(port, 'PUT', '/api/v1/signup/uploads?f=k&e=1&s=sig', 3_000_000, chunked=True)
                            # Only those two, and only with PUT.
                            refused(port, 'POST', '/api/v1/attachments/uploads/u-2', 2_000_001)
                            refused(port, 'PUT', '/api/v1/attachments/upload-url', 2_000_001)
                            refused(port, 'PUT', '/api/v1/signup/uploads/extra', 2_000_001)
                            refused(port, 'PUT', '/api/v1/members/m-1/roles', 2_000_001)
                    # The Core host's anonymous webhooks keep their 1 MiB, and answer the same envelope above it.
                    accepted(core_port, 'POST', '/webhooks/stripe/club-1', 1_048_576)
                    refused(core_port, 'POST', '/webhooks/email/sendgrid', 1_048_577)
                finally:
                    subprocess.run(['docker', 'rm', '-f', container], check=True, capture_output=True)
        finally:
            upstream.shutdown()
            upstream.server_close()


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

    def test_CFG_09_core_runs_without_capabilities_or_privilege_gain(self):
        compose = (ROOT / 'deploy/compose.prod.yaml').read_text()
        core = compose[compose.index('\n  core:\n'):compose.index('\n  caddy:\n')]
        self.assertRegex(core, r'\n    cap_drop: \[ALL\]\n')
        self.assertRegex(core, r'\n    security_opt: \[no-new-privileges:true\]\n')
        self.assertNotRegex(core, r'cap_add|privileged|\n    user:')


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
