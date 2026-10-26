#!/usr/bin/env python3
"""Run one host-locked verification, preserving exact inputs, output, exit and tail."""
import hashlib,json,os,shlex,subprocess,sys,time
from pathlib import Path
root=Path(__file__).resolve().parents[3]
evidence=Path(__file__).resolve().parent
name=sys.argv[1]; command=sys.argv[2:]
paths=sorted({p for top in ['src','seeds'] for p in (root/top).rglob('*') if p.is_file()} | {root/'pom.xml',root/'Dockerfile'})
manifest={str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
(evidence/(name+'-tested-inputs.json')).write_text(json.dumps(manifest,indent=2)+'\n')
heavy='/Users/jordib/Dropbox/Documents/SOFTWARE_CANIC/05-desenvolupament/roadmap-kit/mac/heavy.sh'
(evidence/(name+'.command')).write_text('HEAVY_WAIT_MIN=120 '+shlex.join([heavy,*command])+'\n')
started=time.time()
with (evidence/(name+'.log')).open('w') as output:
    result=subprocess.run([heavy,*command],cwd=root,stdout=output,stderr=subprocess.STDOUT,env={**os.environ,'HEAVY_WAIT_MIN':'120'})
(evidence/(name+'.exit')).write_text(str(result.returncode)+'\n')
lines=(evidence/(name+'.log')).read_text(errors='replace').splitlines()
(evidence/(name+'-tail.txt')).write_text('\n'.join(lines[-40:])+'\n')
changed=[p for p,digest in manifest.items() if not (root/p).exists() or hashlib.sha256((root/p).read_bytes()).hexdigest()!=digest]
(evidence/(name+'-result.json')).write_text(json.dumps({'exit':result.returncode,'seconds':round(time.time()-started,2),'changedInputs':changed},indent=2)+'\n')
print(json.dumps({'exit':result.returncode,'seconds':round(time.time()-started,2),'changedInputs':changed}));print('\n'.join(lines[-12:]))
sys.exit(result.returncode)
