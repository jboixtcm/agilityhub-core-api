#!/usr/bin/env python3
"""Run the task's repository grep without printing possible credential values."""
import json
import re
import subprocess
import sys

pattern = r"sk_live|sk_test_[A-Za-z0-9]{20,}|whsec_[A-Za-z0-9]{20,}"
command = ["rg", "--json", "--hidden", "--glob", "!.git/**", "--glob", "!target/**", pattern, "."]
result = subprocess.run(command, capture_output=True, text=True)
if result.returncode not in (0, 1):
    print("Repository grep failed (output withheld).")
    sys.exit(result.returncode)
suspicious = 0
mentions = 0
for raw in result.stdout.splitlines():
    event = json.loads(raw)
    if event["type"] != "match":
        continue
    data = event["data"]
    line = data["lines"]["text"]
    secret = re.search(r"sk_live_[A-Za-z0-9]+|sk_test_[A-Za-z0-9]{20,}|whsec_[A-Za-z0-9]{20,}", line)
    print(f'{data["path"]["text"]}:{data["line_number"]}: ' + ("secret-shaped match (value withheld)" if secret else "bare sk_live documentation mention"))
    suspicious += bool(secret)
    mentions += not bool(secret)
print(f"rg exit={result.returncode}; bare documentation mentions={mentions}; secret-shaped matches={suspicious}")
sys.exit(1 if suspicious else 0)
