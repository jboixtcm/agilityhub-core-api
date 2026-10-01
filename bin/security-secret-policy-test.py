#!/usr/bin/env python3
"""Prove evidence exceptions still detect a credential on the same line."""
import argparse
import json
from pathlib import Path
import secrets
import subprocess
import tempfile
import uuid

ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--gitleaks', default='gitleaks')
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='e11-secret-policy-') as directory:
        root = Path(directory)
        evidence = root / 'roadmap/evidence/E11-T03'
        evidence.mkdir(parents=True)
        trace = 'o.springdoc.api.AbstractOpenApiResource traceId=' + str(uuid.uuid4()) + ' '
        key = 'Idempotency-Key:"' + str(uuid.uuid4()) + '"'
        dedup = 'dedupKey="' + secrets.token_urlsafe(32) + '"'
        (evidence / 'fixture.log').write_text(trace + '\n' + key + '\n' + dedup + '\n')
        (root / '.env.example').write_text('ATTACHMENT_S3_ACCESS_KEY=\nATTACHMENT_S3_SECRET_KEY=\n')

        def scan():
            result = subprocess.run([args.gitleaks, 'dir', '--no-banner', '--redact', '--config', str(ROOT / '.gitleaks.toml'),
                                     '--report-format', 'json', '--report-path', str(root / 'report.json'), str(root)],
                                    capture_output=True, text=True)
            assert result.returncode in (0, 1), 'Scanner failed to execute'
            findings = json.loads((root / 'report.json').read_text())
            (root / 'report.json').unlink()
            return result.returncode, findings

        code, findings = scan()
        assert code == 0 and not findings, 'Non-secret identifiers must be accepted'
        print('PASS UUID trace/idempotency identifiers and empty example variables')
        (evidence / 'fixture.log').write_text(trace + ' api_key="' + secrets.token_urlsafe(32) + '"\n'
                                            + dedup + ' api_key="' + secrets.token_urlsafe(32) + '"\n'
                                            + 'Idempotency-Key:"' + secrets.token_urlsafe(32) + '"\n')
        (root / '.env.example').write_text('ATTACHMENT_S3_ACCESS_KEY=' + secrets.token_urlsafe(32) + '\n')
        code, findings = scan()
        assert code == 1 and len(findings) == 4, 'Every injected secret must remain detected'
        print('PASS credentials beside trace/dedup, non-UUID key, and populated example key all fail')


if __name__ == '__main__':
    main()
