#!/usr/bin/env python3
"""Disposable authenticated Mongo + encrypted app-file backup/restore proof, no external providers."""
import base64
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]


def run(command, *, env=None, input=None, allowed=(0,)):
    result = subprocess.run(command, input=input, capture_output=True, text=True, env=env, timeout=900)
    if result.returncode not in allowed:
        # Config and tool output may contain private generated credentials.
        raise RuntimeError('Proof command failed: ' + command[0] + ' exit=' + str(result.returncode))
    return result


def main():
    name = 'clubs-volume-proof-' + uuid.uuid4().hex[:10]
    data, work = name + '-data', name + '-work'
    image = os.environ.get('BACKUP_PROOF_IMAGE', 'agilityhub-backup:2')
    with tempfile.TemporaryDirectory(prefix='clubs-volume-proof-') as temporary:
        folder = Path(temporary)
        files, archives = folder / 'files', folder / 'archives'
        files.mkdir(mode=0o700)
        archives.mkdir(mode=0o700)
        marker = files / '.clubs-private-storage'
        marker.write_text('clubs-private-storage-v1\n')
        marker.chmod(0o600)
        (files / 'attachments').mkdir(mode=0o700)
        (files / 'attachments' / 'fictional-document').write_bytes(b'fictional file content\n' * 1000)
        (files / 'exports').mkdir(mode=0o700)
        identity_output = run(['docker', 'run', '--rm', '--entrypoint', 'age-keygen', image]).stdout
        identity = next(line for line in identity_output.splitlines() if line.startswith('AGE-SECRET-KEY-'))
        recipient = run(['docker', 'run', '--rm', '-i', '--entrypoint', 'age-keygen', image, '-y'], input=identity + '\n').stdout.strip()
        env = dict(MONGO_INITDB_ROOT_USERNAME='proofRoot', MONGO_INITDB_ROOT_PASSWORD=secrets.token_hex(32),
                   MONGODB_USERNAME='proofApp', MONGODB_PASSWORD=secrets.token_hex(32), MONGODB_DATABASE='fictional',
                   MONGO_BACKUP_USERNAME='proofBackup', MONGO_BACKUP_PASSWORD=secrets.token_hex(32),
                   MONGO_REPLICA_KEY=base64.b64encode(secrets.token_bytes(512)).decode(),
                   BACKUP_STORAGE_MODE='filesystem', HOSTED_DEMO_ENABLED='true', FILE_STORAGE_MODE='filesystem',
                   BACKUP_LOCAL_DIRECTORY='/backup', BACKUP_FILES_DIRECTORY='/storage', BACKUP_FILES_QUIESCED='true',
                   BACKUP_AGE_RECIPIENT=recipient, BACKUP_S3_PREFIX='mongo/')
        env_file = folder / 'proof.env'
        env_file.write_text(''.join(k + '=' + v + '\n' for k, v in env.items()))
        env_file.chmod(0o600)
        try:
            run(['docker', 'network', 'create', '--internal', name])
            run(['docker', 'volume', 'create', data])
            run(['docker', 'volume', 'create', work])
            run(['docker', 'run', '-d', '--name', name, '--network', name, '--network-alias', 'mongo',
                 '--env-file', str(env_file), '-v', data + ':/data/db', '--tmpfs', '/run/mongo-key:mode=0700',
                 '-v', str(ROOT / 'deploy/mongo-entrypoint.sh') + ':/opt/mongo-entrypoint.sh:ro',
                 '-v', str(ROOT / 'deploy/mongo-health.js') + ':/opt/mongo-health.js:ro',
                 '--entrypoint', 'bash', image, '/opt/mongo-entrypoint.sh'])
            for attempt in range(60):
                result = run(['docker', 'exec', name, 'mongosh', '--quiet', '/opt/mongo-health.js'], allowed=(0, 1))
                if result.returncode == 0:
                    break
                time.sleep(1)
            else:
                raise RuntimeError('Disposable authenticated Mongo did not become ready')
            script = """const admin = db.getSiblingDB('admin');
admin.auth(process.env.MONGODB_USERNAME, process.env.MONGODB_PASSWORD);
const app = db.getSiblingDB('fictional');
app.createCollection('rows');
app.rows.insertMany([{_id:'fixture-1', value:1}, {_id:'fixture-2', value:2}]);
print('FIXTURE_OK');"""
            run(['docker', 'exec', '-i', name, 'mongosh', '--quiet'], input=script)
            base = ['docker', 'run', '--rm', '--network', name, '--env-file', str(env_file),
                    '-v', work + ':/work', '-v', str(files) + ':/storage:ro', '-v', str(archives) + ':/backup',
                    '-v', str(ROOT / 'deploy/backup/backup.py') + ':/opt/backup/backup.py:ro']
            output = run(base + [image, 'backup']).stdout
            line = next(line for line in output.splitlines() if line.startswith('BACKUP_OK '))
            key = next(item[4:] for item in line.split() if item.startswith('key='))
            archive = archives / key
            if not archive.is_file() or b'fictional file content' in archive.read_bytes():
                raise RuntimeError('Encrypted archive missing or contains plaintext')
            print('PASS streaming encrypted Mongo and file archive stored on private volume', flush=True)
            recovery = dict(os.environ, BACKUP_AGE_IDENTITY=identity)
            output = run(base + ['-e', 'BACKUP_AGE_IDENTITY', image, 'restore', '--verify', key], env=recovery).stdout
            if 'FILES_OK entries=4' not in output or 'RESTORE_OK key=' + key not in output:
                raise RuntimeError('Database/file restore proof incomplete')
            print('PASS named archive restores exact Mongo counts and file manifest in isolated work storage', flush=True)
            if (files / 'attachments' / 'fictional-document').read_bytes() != b'fictional file content\n' * 1000:
                raise RuntimeError('Verification changed live file source')
            print('PASS source files unchanged and no external provider used', flush=True)
        finally:
            subprocess.run(['docker', 'rm', '-f', '-v', name], capture_output=True)
            subprocess.run(['docker', 'volume', 'rm', work, data], capture_output=True)
            subprocess.run(['docker', 'network', 'rm', name], capture_output=True)


if __name__ == '__main__':
    main()
