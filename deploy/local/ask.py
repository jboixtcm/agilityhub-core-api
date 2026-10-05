"""Local-only S17 approval fixture. One host; the real bridge must translate it."""
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
from urllib.parse import parse_qs, urlsplit

seen = []


class Ask(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        url = urlsplit(self.path)
        if url.path == '/seen':
            self.send_response(200)
            self.end_headers()
            self.wfile.write(json.dumps(seen).encode())
            return
        seen.append(self.path)
        allowed = url.path == '/internal/domains/allowed' and parse_qs(url.query) == {'host': ['approved.localhost']}
        self.send_response(200 if allowed else 403)
        self.end_headers()


HTTPServer(('0.0.0.0', 8080), Ask).serve_forever()
