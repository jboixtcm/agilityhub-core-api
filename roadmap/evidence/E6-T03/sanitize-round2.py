"""E6-T03 round 2: truncate tokens, hashes and Mongo cluster ids in the round-2 evidence logs (AGENTS.md rule 6), with the
rules of E6-T02's helper, and strip the trailing whitespace of every line so `git diff --check` is clean on them. Only the
round-2 logs (17-*.log and later); round 1's logs were committed and reviewed as they are. Run from the repository root."""
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
    if not number.isdigit() or int(number) < 17:
        continue
    text = open(path, encoding="utf-8").read()
    for pattern, replacement in RULES:
        text = pattern.sub(replacement, text)
    text = re.sub(r"[ \t\r]+$", "", text, flags=re.MULTILINE)
    text = text.rstrip("\n") + "\n" if text.strip() else text
    open(path, "w", encoding="utf-8").write(text)
    print(path, "sanitized")
