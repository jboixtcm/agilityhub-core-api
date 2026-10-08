#!/usr/bin/env python3
"""Prove evidence exceptions still detect a credential on the same line."""
import argparse
import json
from pathlib import Path
import string
import subprocess
import tempfile
import uuid

ROOT = Path(__file__).resolve().parent.parent


def fixture_value():
    # Deterministic high entropy, digits between letters: random URL-safe text can
    # contain a default Gitleaks stopword and make this policy test fail by chance.
    # This value is synthetic, generated only in the disposable scanner fixture.
    return ''.join(letter + str(index % 10) for index, letter in enumerate(string.ascii_letters))


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
        dedup = 'dedupKey="' + fixture_value() + '"'
        (evidence / 'fixture.log').write_text(trace + '\n' + key + '\n' + dedup + '\n')
        (root / '.env.example').write_text('ATTACHMENT_S3_ACCESS_KEY=\nATTACHMENT_S3_SECRET_KEY=\n')
        export_fixture = root / 'src/test/java/com/agilityhub/core/clubs/common/application/ExportFileStoreAdapterTest.java'
        export_fixture.parent.mkdir(parents=True)
        filename_argument = 'downloadUrl("key", "%s");' % 'remesa-2026-09.xml'
        export_fixture.write_text(filename_argument + '\n')

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
        print('PASS the exact fictional export filename argument')
        (evidence / 'fixture.log').write_text(trace + ' api_key="' + fixture_value() + '"\n'
                                            + dedup + ' api_key="' + fixture_value() + '"\n'
                                            + 'Idempotency-Key:"' + fixture_value() + '"\n')
        (root / '.env.example').write_text('ATTACHMENT_S3_ACCESS_KEY=' + fixture_value() + '\n')
        export_fixture.write_text(filename_argument + ' api_key="' + fixture_value() + '"\n'
                                  + 'downloadUrl("key", "' + fixture_value() + '");\n')
        code, findings = scan()
        locations = {(Path(f['File']).relative_to(root).as_posix(), f['StartLine'], f['RuleID']) for f in findings}
        expected = {('roadmap/evidence/E11-T03/fixture.log', line, 'generic-api-key') for line in (1, 2, 3)}
        expected.add(('.env.example', 1, 'generic-api-key'))
        expected.update((export_fixture.relative_to(root).as_posix(), line, 'generic-api-key') for line in (1, 2))
        assert code == 1 and len(findings) == 6 and locations == expected, 'Every injected secret must remain detected'
        print('PASS credentials beside trace/dedup/filename, non-UUID key, populated example key, and changed filename all fail')


if __name__ == '__main__':
    main()
