#!/usr/bin/env python3
"""E11-T06: truncate hashes and random ids (hex runs of 16+ characters) in this session's evidence logs and commands.

Keeps the first 8 characters plus `[truncated]`, as the first session's logs do. Usage: normalize.py FIRST_NUMBER
(only files `NN-*` with NN >= FIRST_NUMBER, `.log` and `.command`, are rewritten; PIT XML and scripts are left alone).
"""
from pathlib import Path
import re
import sys

HERE = Path(__file__).resolve().parent
HEX = re.compile(r'(?<![0-9A-Za-z])([0-9a-fA-F]{8})[0-9a-fA-F]{8,}(?![0-9A-Za-z])')


def main():
    first = int(sys.argv[1])
    for path in sorted(HERE.iterdir()):
        match = re.match(r'(\d+)-', path.name)
        if not match or int(match.group(1)) < first or path.suffix not in ('.log', '.command'):
            continue
        text = path.read_text(errors='replace')
        # Pure decimal runs (counters, epoch millis) are not hashes: only runs with a hex letter are truncated.
        updated = HEX.sub(lambda m: m.group(1) + '[truncated]' if re.search('[a-fA-F]', m.group(0)) else m.group(0), text)
        if updated != text:
            path.write_text(updated)
            print('normalized', path.name)


if __name__ == '__main__':
    main()
