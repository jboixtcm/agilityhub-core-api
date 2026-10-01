#!/usr/bin/env python3
"""E7-T07: replace each `<<TAIL:NN-name.log>>` placeholder of the task report with the literal last 40 lines of that evidence
log, in a fenced block (trailing spaces stripped so that `git diff --check` stays clean; the logs keep them)."""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
TASK = ROOT / "roadmap" / "tasks" / "E7-T07.md"
EVIDENCE = ROOT / "roadmap" / "evidence" / "E7-T07"


def tail(match):
    lines = (EVIDENCE / match.group(1)).read_text().splitlines()[-40:]
    return "```\n" + "\n".join(line.rstrip() for line in lines) + "\n```"


text = TASK.read_text()
filled, count = re.subn(r"<<TAIL:([0-9A-Za-z._-]+)>>", tail, text)
TASK.write_text(filled)
print(f"filled {count} placeholders")
