"""Truncate tokens, hashes and Mongo cluster ids in the E6-T02 evidence logs (AGENTS.md rule 6), as E6-T01's helper did."""
import glob
import re

RULES = [
    (re.compile(r"(electionId=|processId=)([0-9a-f]{4})[0-9a-f]{8,}"), r"\1\2...[truncated]"),
    (re.compile(r"\beyJ[A-Za-z0-9_-]{6}[A-Za-z0-9._-]*"), "eyJ…[truncated]"),
    (re.compile(r"\b([0-9a-f]{8})[0-9a-f]{24,}\b"), r"\1…[truncated]"),
    (re.compile(r"(ipHash=)([A-Za-z0-9_-]{4})[A-Za-z0-9_=-]{8,}"), r"\1\2…[truncated]"),
]
for path in sorted(glob.glob("roadmap/evidence/E6-T02/*.log")):
    text = open(path, encoding="utf-8").read()
    for pattern, replacement in RULES:
        text = pattern.sub(replacement, text)
    open(path, "w", encoding="utf-8").write(text)
    print(path, "sanitized")
