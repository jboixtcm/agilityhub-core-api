#!/usr/bin/env python3
"""Append completed Round 2 verification evidence without altering organizer text."""
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[3]
evidence = root / "roadmap/evidence/E8-T05"
task = root / "roadmap/tasks/E8-T05.md"
heavy = "/Users/jordib/Dropbox/Documents/SOFTWARE_CANIC/05-desenvolupament/roadmap-kit/mac/heavy.sh"
commands = [
    ("78-round2-clean-verify.log", f"{heavy} ./mvnw -q clean verify > roadmap/evidence/E8-T05/78-round2-clean-verify.log 2>&1"),
    ("79-round2-test-summary.log", "python3 roadmap/evidence/E8-T05/round2-summary.py 78 0 > roadmap/evidence/E8-T05/79-round2-test-summary.log 2>&1"),
    ("80-round2-e5-smoke.log", f"{heavy} bin/e5-smoke > roadmap/evidence/E8-T05/80-round2-e5-smoke.log 2>&1"),
    ("81-round2-openapi-snapshot.log", "cp docs/openapi/openapi.json /private/tmp/e8t05-round2-openapi-before.json && bin/openapi-snapshot > roadmap/evidence/E8-T05/81-round2-openapi-snapshot.log 2>&1"),
    ("82-round2-openapi-compare.log", "cmp /private/tmp/e8t05-round2-openapi-before.json docs/openapi/openapi.json > roadmap/evidence/E8-T05/82-round2-openapi-compare.log 2>&1"),
    ("83-round2-diff-check.log", "git diff --check > roadmap/evidence/E8-T05/83-round2-diff-check.log 2>&1"),
]
# Exit codes are recorded only after the foreground command returns.
import json
codes = json.loads((evidence / "round2-command-exits.json").read_text())
assert all(codes[name] == 0 for name, _ in commands)
text = task.read_text()
organizer = text.split("## Organizer verification\n", 1)[1]
summary = (evidence / "79-round2-test-summary.log").read_text()
paragraph = "Fresh final verification: all commands below exited **0**. The clean run includes architecture, audit, event/catalog parity and coverage gates.\n\n"
paragraph += "XML totals (captured before snapshot regeneration): " + "; ".join(line for line in summary.splitlines() if line.startswith(("surefire:", "failsafe:"))) + ". Per-class counts are in log 79; `round2-test-inventory.txt` lists the S12/S13 invocations.\n\n"
for name, command in commands:
    lines = (evidence / name).read_text().splitlines()
    paragraph += f"Command: `{command}`\n\nExit code: **0**. Full output: `roadmap/evidence/E8-T05/{name}`. "
    if lines:
        paragraph += "Literal last 40 lines (all lines when fewer; credential/hash redactions retained):\n\n```text\n" + "\n".join(lines[-40:]) + "\n```\n\n"
    else:
        paragraph += "Output is empty.\n\n"
lines = (evidence / "78-round2-clean-verify.log").read_text().splitlines()
start = next(i for i, line in enumerate(lines) if line.startswith("curl -X POST /api/v1/me/inactivity-periods ->"))
end = next(i for i in range(start, len(lines)) if lines[i].startswith("PASS S13 curl sequence"))
domain = lines[start:end+1] + [line for line in lines if line.startswith("PackBalance movement=")]
paragraph += "The snapshot comparison is byte-identical. Required domain evidence from clean verify (log 78):\n\n```text\n" + "\n".join(domain) + "\n```\n\n"
paragraph += "`LifecycleRulesTest.T_13_07` remains green: 30-10 allowed, 03-11 INACTIVITY_PERIOD, 02-01 allowed; leave day allowed, following day MEMBER_LEAVING. The approval curl case has no bookings; `LifecycleIT.T_13_09` asserts the four cancellations and the extension cases assert history. Round 1's removed-adapter diff remains `adapter-removal.diff`.\n\n"
marker = "Fresh verification in progress; exact commands, exit codes, literal last 40 lines and XML totals are appended here before handoff.\n\n"
assert marker in text
text = text.replace(marker, paragraph, 1)
text = text.replace("**Resumed 2026-10-07.** Continued the partial Round 2 implementation", "**Round 2 complete, 2026-10-07; awaiting organizer verification.** Continued the partial Round 2 implementation", 1)
assert text.split("## Organizer verification\n", 1)[1] == organizer
assert len(text.encode()) < 120 * 1024, f"Report too large: {len(text.encode())}"
task.write_text(text)
print(f"Round 2 report written: {len(text.encode())} bytes; Organizer verification unchanged.")
