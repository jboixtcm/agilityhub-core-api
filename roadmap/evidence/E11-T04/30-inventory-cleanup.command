python3 - <<'CHECK'
from pathlib import Path
import json, re, subprocess
example = Path('deploy/.env.prod.example').read_text()
keys = set(re.findall(r'^([A-Z][A-Z0-9_]*)=', example, re.M))
document = Path('docs/DEPLOY.md').read_text()
compose = '\n'.join(Path(p).read_text() for p in ('deploy/compose.prod.yaml', 'deploy/compose.prod.local.yaml', 'deploy/Caddyfile'))
assert not set(re.findall(r'\$\{([A-Z][A-Z0-9_]*)', compose)) - keys
assert all('`' + key + '`' in document for key in keys)
config = json.loads(subprocess.check_output(['docker', 'compose', '--env-file', 'deploy/.env.prod.example', '-f', 'deploy/compose.prod.yaml', 'config', '--format', 'json'], text=True))
pattern = config['services']['core']['environment']['TRUSTED_PROXY_PATTERN']
address = config['services']['caddy']['networks']['default']['ipv4_address']
assert re.fullmatch(pattern, address)
assert not re.fullmatch(pattern, '198.51.100.123')
print(f'PASS all {len(keys)} example variables are documented; all Compose inputs have examples')
print('PASS trusted proxy pattern matches only the fixed Caddy address')
print('PASS fictional proof resources remaining:')
for kind, args in [('containers', ['ps', '-aq']), ('networks', ['network', 'ls', '-q']), ('volumes', ['volume', 'ls', '-q'])]:
    remaining = subprocess.check_output(['docker', *args, '--filter', 'label=com.docker.compose.project=e11-t04-6634813d'], text=True).strip()
    assert not remaining, kind
    print(kind + '=0')
CHECK
