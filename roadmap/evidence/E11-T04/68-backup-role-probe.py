"""Inspect the pinned Mongo role on an isolated, unexposed fictional container."""
import json
import secrets
import subprocess
import time

image = 'mongo:7.0.41@sha256:8102f674c3d5b5c3b5a248397778b8d148a998c6409f390ff62628eb617f0847'
name = 'e11-backup-role-' + secrets.token_hex(4)
subprocess.run(['docker', 'run', '--rm', '-d', '--name', name, '--network', 'none', image], check=True, capture_output=True)
try:
    for attempt in range(40):
        ready = subprocess.run(['docker','exec',name,'mongosh','--quiet','--eval','db.adminCommand({ping:1})'], capture_output=True)
        if ready.returncode == 0:
            break
        time.sleep(.25)
    code = 'const r=db.getSiblingDB("admin").runCommand({rolesInfo:{role:"backup",db:"admin"},showPrivileges:true}).roles[0]; print(JSON.stringify(r.privileges))'
    rows = json.loads(subprocess.check_output(['docker','exec',name,'mongosh','--quiet','--eval',code],text=True))
    print(json.dumps(rows,indent=2))
    assert 'dbStats' not in {a for row in rows for a in row['actions']}
    assert 'collStats' in {a for row in rows for a in row['actions']}
    print('PASS built-in backup role permits collStats but not dbStats; no additional role needed')
finally:
    subprocess.run(['docker','rm','-f',name],check=True,capture_output=True)
    print('CLEANUP isolated role probe removed')
