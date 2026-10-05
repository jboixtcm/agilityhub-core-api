#!/usr/bin/env python3
"""Capture current CI failures without publishing raw request or credential logs."""
import json
import subprocess

RUN = '37239779509'
document = json.loads(subprocess.check_output(
    ['gh', 'run', 'view', RUN, '--json', 'jobs,conclusion,status,url'], text=True))
print(document['url'])
print('workflow', document['status'], document['conclusion'])
for job in document['jobs']:
    print(job['name'], job['status'], job['conclusion'])
log = subprocess.check_output(['gh', 'run', 'view', RUN, '--log-failed'], text=True)
markers = ('[ERROR] Tests run:', '[ERROR]   NotificationMatrixTest.',
           '[ERROR]   NotificationCatalogContractTest.', '[ERROR]   S04ErrorContractTest.')
for line in log.splitlines():
    if any(marker in line for marker in markers):
        print(line[line.index('[ERROR]'):])
raise SystemExit(0 if document['conclusion'] == 'success' else 1)
