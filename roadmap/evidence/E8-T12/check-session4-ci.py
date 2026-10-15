#!/usr/bin/env python3
"""Report CI status and scanner metadata without emitting matched values."""
import collections
import json
import re
import subprocess


def output(*args):
    return subprocess.check_output(args, text=True)


run_id = "38702152670"
run = json.loads(output("gh", "run", "view", run_id, "--json", "headSha,status,conclusion,jobs,url"))
head = output("git", "rev-parse", "HEAD").strip()
print("Published run:", run["url"])
print("Run status:", run["status"], "; conclusion:", run["conclusion"] or "pending")
print("Published run matches current HEAD:", run["headSha"] == head)
print("Current HEAD runs:", output("gh", "run", "list", "--commit", head,
                                   "--json", "databaseId,status,conclusion,url").strip())
for job in run["jobs"]:
    print("Job:", job["name"], "; status:", job["status"], "; conclusion:", job["conclusion"] or "pending")
scan = next(job for job in run["jobs"] if job["name"] == "Secret scan")
raw = output("gh", "api", f"repos/jboixtcm/agilityhub-core-api/actions/jobs/{scan['databaseId']}/logs")
files = collections.Counter()
rules = collections.Counter()
for line in raw.splitlines():
    clean = re.sub(r"\x1b\[[0-9;]*m", "", line)
    match = re.search(r"\b(File|RuleID):\s*(.+)", clean)
    if match:
        (files if match[1] == "File" else rules)[match[2]] += 1
for rule, count in sorted(rules.items()):
    print("Rule:", rule, "; findings:", count)
for path, count in sorted(files.items()):
    comparison = subprocess.run(["git", "diff", "--quiet", run["headSha"], "--", path])
    assert comparison.returncode in (0, 1)
    print("File:", path, "; findings:", count, "; unchanged in current checkout:", comparison.returncode == 0)
print("No scanner matches or source manifest contents were printed or saved.")
if scan["conclusion"] == "failure":
    print("BLOCKED: required green CI cannot pass while the completed Secret scan job fails.")
    raise SystemExit(1)
