#!/usr/bin/env python3
"""Retain the scanner's installed versions even when patched packages have no findings."""
import json
import os
from pathlib import Path
import runpy
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
scanner = runpy.run_path(str(ROOT / 'bin/security-scan'))
cache = ROOT / '.local/security-tools'
with tempfile.TemporaryDirectory(prefix='e11-version-inventory-') as directory:
    report = Path(directory) / 'inventory.json'
    command = [str(cache / ('trivy-' + scanner['VERSION'])), 'rootfs', '--scanners', 'vuln',
               '--no-progress', '--format', 'json', '--output', str(report), '--exit-code', '0',
               '--timeout', '15m', 'target']
    print('SCANNER_COMMAND ' + shlex.join(command), flush=True)
    subprocess.run(command, cwd=ROOT, env=dict(os.environ, TRIVY_CACHE_DIR=str(cache / 'db')), check=True)
    document = json.loads(report.read_text())
    versions = {}
    for result in document.get('Results', []):
        for package in result.get('Packages', []):
            name = package.get('Name', '')
            if name.startswith(('io.netty:', 'org.apache.tomcat.embed:')):
                versions.setdefault(name, set()).add(package['Version'])
    assert 'org.apache.tomcat.embed:tomcat-embed-core' in versions
    assert 'io.netty:netty-handler' in versions
    for name, values in versions.items():
        expected = '4.1.137.Final' if name.startswith('io.netty:') else '10.1.60'
        # Netty native transports have their own version family; the runtime modules use the BOM.
        if not name.startswith('io.netty:netty-tcnative'):
            assert values == {expected}, (name, sorted(values))
    print(json.dumps({'javaPackages': scanner['java_inventory'](document),
                      'installedVersions': {name: sorted(values) for name, values in sorted(versions.items())}}, indent=2))
    assert not scanner['findings'](document)['fixableCritical']
    print('PASS scanner inventory contains the patched runtime versions and no fixable CRITICAL')
