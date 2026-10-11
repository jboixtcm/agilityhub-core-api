#!/usr/bin/env python3
"""Validate the strict audit's known Springdoc trace-ID false positives; reject any other finding.

This does not configure or suppress the repository scanner. Historical logs remain untouched.
"""
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[3]
UUID = r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'
TRACE = re.compile(r'o\.springdoc\.api\.AbstractOpenApiResource traceId=(' + UUID + r') ')


def main():
    rows = json.loads(Path(sys.argv[1]).read_text())
    for row in rows:
        path = ROOT / row['File']
        assert path.resolve().is_relative_to(Path(__file__).resolve().parent)
        line = path.read_text().splitlines()[row['StartLine'] - 1]
        match = TRACE.search(line)
        assert row['RuleID'] == 'generic-api-key' and match is not None, 'Unclassified finding'
        assert match.group(0).replace(match.group(1), 'REDACTED') == row['Match'], 'Unexpected matched content'
        print(f"TRACE_UUID {row['File']}:{row['StartLine']}; Springdoc correlation field, no credential")
    print(f'PASS {len(rows)} findings classified; no scanner rules or historical logs changed.')


if __name__ == '__main__':
    main()
