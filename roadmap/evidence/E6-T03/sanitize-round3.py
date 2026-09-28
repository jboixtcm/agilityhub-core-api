"""E6-T03 round 3: the rules of sanitize-round2.py (truncate tokens, hashes and Mongo cluster ids, AGENTS.md rule 6; strip the
trailing whitespace of every line so `git diff --check` is clean) over the round-3 logs only (31-*.log and later); the earlier
logs were committed and reviewed as they are. Prints how many substitutions each rule made per file. Run from the
repository root."""
import glob
import re

RULES = [
    (re.compile(r"(electionId=|processId=)([0-9a-f]{4})[0-9a-f]{8,}"), r"\1\2...[truncated]"),
    (re.compile(r"\beyJ[A-Za-z0-9_-]{6}[A-Za-z0-9._-]*"), "eyJ…[truncated]"),
    (re.compile(r"\b([0-9a-f]{8})[0-9a-f]{24,}\b"), r"\1…[truncated]"),
    (re.compile(r"(ipHash=)([A-Za-z0-9_-]{4})[A-Za-z0-9_=-]{8,}"), r"\1\2…[truncated]"),
]
for path in sorted(glob.glob("roadmap/evidence/E6-T03/*.log")):
    number = path.rsplit("/", 1)[-1].split("-", 1)[0]
    if not number.isdigit() or int(number) < 31:
        continue
    text = open(path, encoding="utf-8").read()
    counts = []
    for pattern, replacement in RULES:
        text, count = pattern.subn(replacement, text)
        counts.append(count)
    text, trailing = re.subn(r"[ \t\r]+$", "", text, flags=re.MULTILINE)
    text = text.rstrip("\n") + "\n" if text.strip() else text
    open(path, "w", encoding="utf-8").write(text)
    print(f"{path} sanitized: cluster ids {counts[0]}, JWTs {counts[1]}, long hex {counts[2]}, ip hashes {counts[3]}, trailing whitespace {trailing}")
