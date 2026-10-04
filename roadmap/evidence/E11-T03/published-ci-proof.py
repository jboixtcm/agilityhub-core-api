#!/usr/bin/env python3
"""Capture CI states and the broken-image proof without raw logs or credentials."""
import json
import subprocess
import sys

run_id = sys.argv[1]
document = json.loads(subprocess.check_output(
    ['gh', 'run', 'view', run_id, '--json', 'jobs,conclusion,status,url'], text=True))
print(document['url'])
print('workflow', document['status'], document['conclusion'])
for job in document['jobs']:
    print(job['name'], job['status'], job['conclusion'])
    if job['name'] != 'Scan image (amd64)' or job['conclusion'] != 'success':
        continue
    for step in job['steps']:
        if any(word in step['name'].lower() for word in ('compose', 'scan image')):
            print('step:', step['name'], '->', step['conclusion'])
    log = subprocess.check_output(
        ['gh', 'api', f'repos/jboixtcm/agilityhub-core-api/actions/jobs/{job["databaseId"]}/logs'], text=True)
    for line in log.splitlines():
        if (' Container ' in line and 'Error' in line
                or 'dependency failed to start: container' in line
                or 'PASS: the compose smoke refused the broken image' in line):
            print(line)
