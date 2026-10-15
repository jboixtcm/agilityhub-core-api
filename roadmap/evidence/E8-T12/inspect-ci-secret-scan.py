#!/usr/bin/env python3
"""Inspect scanner metadata only, never findings, matched values or raw logs."""
import json
import re
import subprocess
from collections import Counter

run = '38702152670'
info = json.loads(subprocess.check_output(['gh', 'run', 'view', run, '--json', 'headSha,status,conclusion,jobs'], text=True))
head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
print('Published CI matches current HEAD:', info['headSha'] == head)
changed = subprocess.check_output(['git', 'diff', '--name-only', info['headSha'], head], text=True).splitlines()
print('Files changed since inspected CI:', changed)
print('HEAD CI runs:', subprocess.check_output(['gh', 'run', 'list', '--commit', head,
      '--json', 'databaseId,status,conclusion,url'], text=True).strip())
print('Run:', run, 'status:', info['status'], 'conclusion:', info['conclusion'])
job = next(job for job in info['jobs'] if job['name'] == 'Secret scan')
print('Secret scan:', job['conclusion'])
raw = subprocess.check_output(['gh', 'api', f"repos/jboixtcm/agilityhub-core-api/actions/jobs/{job['databaseId']}/logs"], text=True)
rules = Counter()
files = Counter()
for line in raw.splitlines():
    clean = re.sub(r'\x1b\[[0-9;]*m', '', line)
    match = re.search(r'\b(File|RuleID|StartLine|EndLine):\s*(.+)', clean)
    if match and match.group(1) == 'RuleID':
        rules[match.group(2)] += 1
    if match and match.group(1) == 'File':
        files[match.group(2)] += 1
    count = re.search(r'leaks found:\s*\d+', clean)
    if count:
        print(count.group(0))
for name, count in sorted(rules.items()):
    print(f'Rule {name}: {count} findings')
for name, count in sorted(files.items()):
    print(f'File {name}: {count} findings; unchanged since CI: {name not in changed}')
