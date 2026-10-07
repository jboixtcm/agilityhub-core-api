#!/usr/bin/env python3
"""Record the final task scope using only read-only git commands."""
from pathlib import Path
import subprocess
import json

root = Path.cwd()
p = Path('roadmap/tasks/E8-T05.md')
current = p.read_text()
original = subprocess.check_output(['git', 'show', 'HEAD:'+str(p)], text=True)
marker = '## Organizer verification'
assert current.split(marker, 1)[1] == original.split(marker, 1)[1]
assert p.stat().st_size < 120_000
changed = subprocess.check_output(['git', 'diff', '--name-only'], text=True).splitlines()
untracked = subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard'], text=True).splitlines()
assert [f for f in changed if f.startswith('roadmap/tasks/')] == [str(p)]
assert 'roadmap/ROADMAP.md' not in changed
assert not any(f.startswith('roadmap/tasks/') for f in untracked)
files = sorted(set(changed+untracked))
Path('roadmap/evidence/E8-T05/files-changed.txt').write_text('\n'.join(f for f in files if not f.startswith('roadmap/evidence/'))+'\n')
print('Only roadmap task E8-T05 changed; ROADMAP and Organizer verification are unchanged.')
print('Task size:', p.stat().st_size, 'bytes (limit 120000).')
print('Files changed inventory: roadmap/evidence/E8-T05/files-changed.txt')
print('Review: club-local dates; dog-owner family boundary; exact error details/status/nulls; tenant-scoped repositories; transactional cancellation/audit/outbox; optimistic versions and commit-time cache visibility.')
print('No UI changes. No git write commands used; the publish wrapper owns commit/push.')

api = json.loads(Path('docs/openapi/openapi.json').read_text())
for path in ('/seat-holds', '/bookings', '/waitlist-entries', '/waitlist-entries/{id}/claim', '/activity-registrations', '/training-bookings'):
    assert 'MEMBER_LEAVING' in api['paths']['/api/v1'+path]['post']['responses']['422']['description'], path
print('Published MEMBER_LEAVING response verified for all six booking, waitlist, activity and training operations.')
