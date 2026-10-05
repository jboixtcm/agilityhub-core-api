from pathlib import Path
import json
import re
import subprocess

example = Path('deploy/.env.prod.example').read_text()
keys = set(re.findall(r'^([A-Z][A-Z0-9_]*)=', example, re.M))
doc = Path('docs/DEPLOY.md').read_text()
compose = '\n'.join(Path(p).read_text() for p in ('deploy/compose.prod.yaml', 'deploy/compose.prod.local.yaml', 'deploy/Caddyfile'))
assert not set(re.findall(r'\$\{([A-Z][A-Z0-9_]*)', compose)) - keys
assert all('`' + key + '`' in doc for key in keys)
config = json.loads(subprocess.check_output(['docker', 'compose', '--env-file', 'deploy/.env.prod.example', '-f', 'deploy/compose.prod.yaml', 'config', '--format', 'json'], text=True))
pattern = config['services']['core']['environment']['TRUSTED_PROXY_PATTERN']
address = config['services']['caddy']['networks']['default']['ipv4_address']
assert re.fullmatch(pattern, address)
assert not re.fullmatch(pattern, '198.51.100.123')
assert 'MIGRATION_BANK_KEY' not in keys
assert {'BILLING_BANK_KEY', 'BILLING_SECRETS_KEY', 'BACKUP_AGE_RECIPIENT'} <= keys
assert not {'BACKUP_ENCRYPTION_KEY', 'BACKUP_AGE_IDENTITY', 'BACKUP_RETENTION_DAYS'} & keys
print(f'PASS all {len(keys)} example variables are documented; all Compose inputs have examples')
print('PASS trust regex matches only the fixed Caddy address; cron inventory has no private age identity')
print('Variables: ' + ', '.join(sorted(keys)))
