#!/usr/bin/env python3
"""Check task boundaries and the report's exact command, exit and output-tail evidence."""
from pathlib import Path
import subprocess

root=Path(__file__).resolve().parent
task=Path('roadmap/tasks/E10-T01.md')
text=task.read_text()
before=subprocess.check_output(['git','show',(root/'round2-base-revision.txt').read_text().strip()+':'+str(task)],text=True)
assert before.split('## Organizer verification',1)[1] == text.split('## Organizer verification',1)[1]
changed=subprocess.check_output(['git','diff','--name-only'],text=True).splitlines()
assert not any(p.startswith('roadmap/tasks/') and p!=str(task) for p in changed)
assert 'roadmap/ROADMAP.md' not in changed
assert not any(p.startswith('docs/specs/00-transversal/') for p in changed)
assert task.stat().st_size<120_000
section=text.split('#### Round 2 verification evidence',1)[1].split('## Organizer verification',1)[0]
count=0
for command_file in sorted(root.glob('*-round2-*.command.txt')):
    name=command_file.name.removesuffix('.command.txt')
    if '**'+name+'.**' not in section: continue
    command=command_file.read_text().strip()
    code=(root/(name+'.exit')).read_text().strip()
    tail='\n'.join((root/(name+'.log')).read_text().splitlines()[-40:])
    assert 'Command: `'+command+'`' in section, name
    assert 'Exit: `'+code+'`' in section, name
    assert '```text\n'+tail+'\n```' in section, name
    count+=1
assert count>=10
print('Only E10-T01 task state/report changed; Organizer verification, ROADMAP and closed catalogs are unchanged.')
print(f'{count} final command/exit/literal-tail blocks match retained evidence; task size {task.stat().st_size} bytes.')
print('Git write commands were not used; publication and final-commit CI remain outside this working-tree session.')
