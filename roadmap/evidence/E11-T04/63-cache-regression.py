"""Reject unordered SPA headers, using the real Caddy snippets in memory."""
from pathlib import Path
from unittest.mock import patch
import unittest
from deploy.test_round2 import DeploymentReviewTest

original = Path.read_text

def without_ordered_route(path, *args, **kwargs):
    text = original(path, *args, **kwargs)
    if path.name == 'Caddyfile':
        old = '''        route {
            try_files {path} /index.html
            header /index.html Cache-Control "no-cache"
            file_server
        }'''
        assert old in text
        text = text.replace(old, '''        try_files {path} /index.html
        header /index.html Cache-Control "no-cache"
        file_server''')
    return text

with patch.object(Path, 'read_text', without_ordered_route):
    result = unittest.TextTestRunner(verbosity=2).run(unittest.TestSuite([
        DeploymentReviewTest('test_E11_T04_05_real_caddy_index_headers')]))
assert result.failures and not result.errors, 'Runtime test must reject unordered cache headers'
print('PASS real Caddy runtime test rejects the reinstated cache defect; checkout unchanged')
