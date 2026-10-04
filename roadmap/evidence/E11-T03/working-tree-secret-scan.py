#!/usr/bin/env python3
"""Scan exactly this task's publishable source/evidence, without build caches."""
from pathlib import Path
import argparse, json, subprocess, tempfile, shutil
parser = argparse.ArgumentParser()
parser.add_argument('--gitleaks', default='gitleaks')
args = parser.parse_args()
ROOT = Path(__file__).resolve().parents[3]
files = set(subprocess.check_output(['git','diff','--name-only','bacef6e'],cwd=ROOT,text=True).splitlines())
files.update(subprocess.check_output(['git','ls-files','--others','--exclude-standard'],cwd=ROOT,text=True).splitlines())
with tempfile.TemporaryDirectory(prefix='e11-publishable-scan-') as directory:
    target = Path(directory)
    for name in files:
        source = ROOT/name
        if source.is_file() and not name.startswith('.local/'):
            destination = target/name
            destination.parent.mkdir(parents=True,exist_ok=True)
            shutil.copy2(source,destination)
    print('Scanned publishable changed files; local caches excluded',flush=True)
    findings = target / 'scan-report.json'
    command = [args.gitleaks,'dir','--no-banner','--redact','--config',str(ROOT/'.gitleaks.toml'),
               '--report-format','json','--report-path',str(findings),str(target)]
    print('COMMAND ' + ' '.join(command),flush=True)
    result = subprocess.run(command,cwd=ROOT)
    if findings.exists():
        for finding in json.loads(findings.read_text()):
            print(json.dumps({key: finding[key] for key in ('File', 'StartLine', 'RuleID')}), flush=True)
    raise SystemExit(result.returncode)
