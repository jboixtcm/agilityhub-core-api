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
    backend = environment.get('BACKUP_STORAGE_MODE', 's3')
    if backend not in ('s3', 'filesystem'):
        raise ValueError('Invalid BACKUP_STORAGE_MODE')
    filesystem = backend == 'filesystem'
    if filesystem:
        if os.environ.get('DEPLOY_LOCAL') == '1':
            raise ValueError('Filesystem internal storage cannot use the local proof override')
        command += ['-f', str(root / 'deploy/compose.filesystem.yaml')]
        resolved = subprocess.run(command + ['config', '--format', 'json'], capture_output=True, text=True)
        if resolved.returncode:
            raise ValueError('Filesystem Compose configuration failed')
        environment = json.loads(resolved.stdout)['services']['backup']['environment']
        if environment.get('BACKUP_STORAGE_MODE') != 'filesystem':
            raise ValueError('Filesystem override changed backup mode')
    if filesystem and (environment.get('HOSTED_DEMO_ENABLED') != 'true' or environment.get('FILE_STORAGE_MODE') != 'filesystem'):
        raise ValueError('Filesystem backups require explicit internal filesystem policy')
    required = ['BACKUP_LOCAL_DIRECTORY', 'BACKUP_FILES_DIRECTORY'] if filesystem else ['BACKUP_S3_BUCKET', 'BACKUP_S3_ACCESS_KEY', 'BACKUP_S3_SECRET_KEY']
    if arguments[0] == 'backup':
        required.append('BACKUP_AGE_RECIPIENT')
    elif not os.environ.get('BACKUP_AGE_IDENTITY'):
        raise ValueError('BACKUP_AGE_IDENTITY is required for verification only')
    for key in required:
        if not environment.get(key):
            raise ValueError(f'{key} is required; no container, dump or S3 object was created')
    if arguments[0] == 'backup' and not environment['BACKUP_AGE_RECIPIENT'].startswith('age1'):
        raise ValueError('BACKUP_AGE_RECIPIENT must be a public age recipient')
    if not filesystem and (not environment.get('BACKUP_S3_PREFIX', '').endswith('/') or environment['BACKUP_S3_PREFIX'] == '/'):
        raise ValueError('BACKUP_S3_PREFIX must be nonempty and end with /')
    # No --build or --pull: image preparation belongs to deployment, not cron.
    extra = ['-e', 'BACKUP_AGE_IDENTITY'] if arguments[0] == 'restore' else []
    if filesystem and arguments[0] == 'backup':
        running = subprocess.run(command + ['ps', '--status', 'running', '--quiet', 'core'], capture_output=True, text=True)
        if running.returncode or running.stdout.strip():
            raise ValueError('Stop the core application before a filesystem backup; no dump was created')
        extra += ['-e', 'BACKUP_FILES_QUIESCED=true']
    return subprocess.call(command + ['run', '--rm', '--no-deps', '-T', *extra, 'backup'] + arguments)


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (ValueError, KeyError) as error:
        print(f'Configuration error: {error}', file=sys.stderr)
        sys.exit(2)
