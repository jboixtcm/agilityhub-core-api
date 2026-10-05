from pathlib import Path
import re
import subprocess

root = Path.cwd()
task = Path('roadmap/tasks/E11-T04.md')
report = task.read_text()
baseline = subprocess.check_output(['git','show','HEAD:roadmap/tasks/E11-T04.md'],text=True)
assert report.split('## Organizer verification',1)[1] == baseline.split('## Organizer verification',1)[1]
assert task.stat().st_size < 120000
changed = subprocess.check_output(['git','diff','--name-only'],text=True).splitlines()
assert not any(p.startswith('src/') or p == '.env.example' or p == 'roadmap/ROADMAP.md' or
               (p.startswith('roadmap/tasks/') and p != str(task)) for p in changed)
old_doc = subprocess.check_output(['git','show','HEAD:docs/DEPLOY.md'],text=True)
new_doc = Path('docs/DEPLOY.md').read_text()
for key in ('BILLING_BANK_KEY','MIGRATION_BANK_KEY'):
    row = next(line for line in old_doc.splitlines() if line.startswith('| `' + key + '` |'))
    assert row in new_doc
    paragraph = re.search(r'^- `' + key + r'`.*?(?=^- |\Z)', old_doc, re.M | re.S).group()
    assert paragraph in new_doc
print('PASS Organizer verification, other tasks and protected bank-key text unchanged; no application source/resource edits')
round2 = report.split('### Round 2 report',1)[1].split('## Organizer verification',1)[0]
logs = re.findall(r'Full log: `(roadmap/evidence/E11-T04/[^`]+\.log)`', round2)
assert len(logs) >= 22
for name in logs:
    log = Path(name)
    stem = log.with_suffix('')
    assert stem.with_suffix('.command').read_text().strip() in round2
    assert 'Exit: **' + stem.with_suffix('.exit').read_text().strip() + '**' in round2
    tail = '\n'.join(log.read_text().splitlines()[-40:])
    assert not tail or tail in round2, name
print(f'PASS {len(logs)} evidence sections contain exact commands, exits and literal log tails')
new_logs = [p for p in Path('roadmap/evidence/E11-T04').glob('*.log') if int(p.name[:2]) >= 54]
ignored = subprocess.run(['git','check-ignore',*[str(p) for p in new_logs]],capture_output=True,text=True)
assert ignored.returncode == 1 and not ignored.stdout
for log in new_logs:
    data = log.read_text()
    assert not re.search(r'AGE-SECRET-KEY-1[0-9A-Z]{20,}|eyJ[A-Za-z0-9_-]{30,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+|(?:Cookie: ah_refresh=|Authorization: Bearer )[^\s\x27\x22]{12,}',data), log
print('PASS new logs are unignored and contain no private age identity, full JWT or populated authorization/cookie header')
project = 'e11-t04-e7903487'
for label, args in [('containers',['ps','-aq']),('networks',['network','ls','-q']),('volumes',['volume','ls','-q'])]:
    remaining = subprocess.check_output(['docker',*args,'--filter','label=com.docker.compose.project='+project],text=True).strip()
    assert not remaining, label
    print('PASS final project ' + label + '=0')
print('PASS task bytes=' + str(task.stat().st_size))
