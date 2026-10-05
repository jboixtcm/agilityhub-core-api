from pathlib import Path
import json
import re
import subprocess
import tempfile
import zipfile

image='ghcr.io/jboixtcm/agilityhub-core-api:main'
info=json.loads(subprocess.check_output(['docker','image','inspect',image],text=True))[0]
print('Image:',image)
print('Immutable local image ID:',info['Id'][:23]+'...[truncated]')
for digest in info.get('RepoDigests',[]):
 print('Registry manifest:',re.sub(r'(sha256:[a-f0-9]{12})[a-f0-9]+',r'\1...[truncated]',digest))
print('Source revision label:',(info['Config'].get('Labels') or {}).get('org.opencontainers.image.revision','absent'))
cid=subprocess.check_output(['docker','create',image],text=True).strip()
try:
 with tempfile.TemporaryDirectory() as d:
  subprocess.run(['docker','cp',cid+':/app/app.jar',d+'/app.jar'],check=True,capture_output=True)
  with zipfile.ZipFile(d+'/app.jar') as z:
   print(z.read('META-INF/build-info.properties').decode().strip())
   for name in ['BankAccountVault','ProviderSecretVault']:
    assert any(n.endswith('/'+name+'.class') for n in z.namelist())
    print('PASS E8-T01 class:',name)
   config=z.read('BOOT-INF/classes/application.yml').decode()
   for key in ['BILLING_BANK_KEY','BILLING_SECRETS_KEY']:
    assert key in config
    print('PASS application environment contract:',key)
finally:
 subprocess.run(['docker','rm',cid],capture_output=True,check=True)
