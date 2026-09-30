#!/usr/bin/env python3
"""Host entry point. Resolve Compose's environment privately before creating anything."""
import json
import os
from pathlib import Path
import subprocess
import sys


def main():
    arguments = sys.argv[1:]
    if not arguments or arguments[0] not in ('backup', 'restore'):
        raise ValueError('Expected backup or restore')
    if arguments[0] == 'backup' and len(arguments) != 1:
        raise ValueError('Usage: bin/backup-mongo')
    if arguments[0] == 'restore' and (len(arguments) not in (2, 3) or arguments[1] != '--verify'):
        raise ValueError('Usage: bin/restore-mongo --verify [object-key]')
    root = Path(__file__).resolve().parent.parent
    env_file = os.environ.get('DEPLOY_ENV_FILE', str(root / 'deploy/.env.prod'))
    command = ['docker', 'compose', '--env-file', env_file, '--profile', 'ops']
    project = os.environ.get('COMPOSE_PROJECT_NAME')
    if project:
        command += ['-p', project]
    command += ['-f', str(root / 'deploy/compose.prod.yaml')]
    if os.environ.get('DEPLOY_LOCAL') == '1':
        command += ['-f', str(root / 'deploy/compose.prod.local.yaml')]
    config = subprocess.run(command + ['config', '--format', 'json'], capture_output=True, text=True)
    if config.returncode:
        raise ValueError('Compose configuration failed; check DEPLOY_ENV_FILE and required variables')
    environment = json.loads(config.stdout)['services']['backup']['environment']
    for key in ('BACKUP_ENCRYPTION_KEY', 'BACKUP_S3_BUCKET', 'BACKUP_S3_ACCESS_KEY', 'BACKUP_S3_SECRET_KEY'):
        if not environment.get(key):
            raise ValueError(f'{key} is required; no container, dump or S3 object was created')
    if not environment['BACKUP_ENCRYPTION_KEY'].startswith('AGE-SECRET-KEY-1'):
        raise ValueError('BACKUP_ENCRYPTION_KEY must be an age identity')
    if not environment.get('BACKUP_S3_PREFIX', '').endswith('/') or environment['BACKUP_S3_PREFIX'] == '/':
        raise ValueError('BACKUP_S3_PREFIX must be nonempty and end with /')
    if not 1 <= int(environment['BACKUP_RETENTION_DAYS']) <= 36500:
        raise ValueError('BACKUP_RETENTION_DAYS must be 1..36500')
    # No --build or --pull: image preparation belongs to deployment, not cron.
    return subprocess.call(command + ['run', '--rm', '--no-deps', '-T', 'backup'] + arguments)


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (ValueError, KeyError) as error:
        print(f'Configuration error: {error}', file=sys.stderr)
        sys.exit(2)
