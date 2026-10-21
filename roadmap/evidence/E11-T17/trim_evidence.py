#!/usr/bin/env python3
"""E11-T17 step 8 (N7 of E11-T10's review): trim one oversized evidence log in place.

Kept: the first 40 and the last 40 lines; every summary line (Maven/Surefire/Failsafe totals, the per-class summary lines,
`FAILED …`, `exit=`, the host lock's lines); every failure block (a test's `<<< FAILURE!`/`<<< ERROR!` header or a
`[ERROR]   Class.test:line` entry of the Results, and the lines that follow it up to the next log or Maven line), of which
the first 60 and the last 10 lines. A kept line longer than 400 characters is cut (the OpenAPI JSON dumped in one line).
Each omitted run of lines becomes one `[… E11-T17 trim: N lines omitted …]` line. A header line says what was done.

Usage: python3 -I trim_evidence.py <log>
"""
import os
import re
import sys

HEAD = TAIL = 40
BLOCK_HEAD, BLOCK_TAIL = 60, 10
MAX_LINE = 400
SUMMARY = re.compile(r"Tests run:|<<< (FAILURE|ERROR)!|^\[(ERROR|WARNING)\]|^\s*FAILED |^\s*\S+ tests \d+ failures \d+|^(surefire|failsafe) tests|^exit=|^heavy\.sh:")
BLOCK_START = re.compile(r"<<< (FAILURE|ERROR)! *$|^\[ERROR\] {3}\S")
BLOCK_END = re.compile(r"^\d{2}:\d{2}:\d{2}\.\d{3} |^\d{4}-\d{2}-\d{2}T\d{2}:|^\[(INFO|ERROR|WARNING)\]|Tests run:")


def kept_lines(lines):
    keep = set(range(min(HEAD, len(lines)))) | set(range(max(0, len(lines) - TAIL), len(lines)))
    i = 0
    while i < len(lines):
        if SUMMARY.search(lines[i]):
            keep.add(i)
        if BLOCK_START.search(lines[i]):
            end = i + 1
            while end < len(lines) and not BLOCK_END.search(lines[end]):
                end += 1
            block = range(i, end)
            keep.update(block[:BLOCK_HEAD])
            keep.update(block[-BLOCK_TAIL:])
            i = max(i + 1, end)
            continue
        i += 1
    return keep


def trim(lines):
    keep = kept_lines(lines)
    out, omitted = [], 0
    for index, line in enumerate(lines):
        if index not in keep:
            omitted += 1
            continue
        if omitted:
            out.append(f"[… E11-T17 trim: {omitted} lines omitted …]")
            omitted = 0
        body = line.rstrip("\n")
        if len(body) > MAX_LINE:
            body = f"{body[:MAX_LINE]} […E11-T17 trim: {len(body) - MAX_LINE} more characters]"
        out.append(body)
    if omitted:
        out.append(f"[… E11-T17 trim: {omitted} lines omitted …]")
    return out


def main(path):
    with open(path, encoding="utf-8", errors="replace") as source:
        lines = source.readlines()
    before = os.path.getsize(path)
    out = trim(lines)
    header = (f"# E11-T17 step 8 (N7 of E11-T10's review): trimmed from {before} bytes / {len(lines)} lines by "
              f"roadmap/evidence/E11-T17/trim_evidence.py — kept the first {HEAD} and last {TAIL} lines, every summary line and every "
              f"failure block (its first {BLOCK_HEAD} and last {BLOCK_TAIL} lines); lines cut at {MAX_LINE} characters.")
    with open(path, "w", encoding="utf-8") as target:
        target.write("\n".join([header] + out) + "\n")
    print(f"{path}: {before} bytes, {len(lines)} lines -> {os.path.getsize(path)} bytes, {len(out) + 1} lines")


if __name__ == "__main__":
    main(sys.argv[1])
