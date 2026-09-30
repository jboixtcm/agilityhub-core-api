"""Encrypted full-replica backup and isolated restore verification; no live restore mode."""
import datetime as dt
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import tarfile
import tempfile
import time
import uuid

import boto3
from botocore.config import Config
from pymongo import MongoClient
from pymongo.errors import OperationFailure


class Failure(Exception):
    pass


def run(command, **kwargs):
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=900, **kwargs)
    if result.returncode:
        # Database tools may put document values and credential-bearing URIs in errors.
        raise Failure(f'{command[0]} failed (exit {result.returncode}); private tool output suppressed')
    return result.stdout


def configuration():
    for key in ('BACKUP_ENCRYPTION_KEY', 'BACKUP_S3_BUCKET', 'BACKUP_S3_ACCESS_KEY',
                'BACKUP_S3_SECRET_KEY', 'MONGO_BACKUP_USERNAME', 'MONGO_BACKUP_PASSWORD'):
        if not os.environ.get(key):
            raise Failure(f'Missing {key}')
    prefix = os.environ.get('BACKUP_S3_PREFIX', 'mongo/')
    if not re.fullmatch(r'[A-Za-z0-9_/-]+/', prefix) or '..' in prefix or prefix.startswith('/'):
        raise Failure('Invalid BACKUP_S3_PREFIX')
    retention = int(os.environ.get('BACKUP_RETENTION_DAYS', '30'))
    if not 1 <= retention <= 36500:
        raise Failure('Invalid BACKUP_RETENTION_DAYS')
    # Validate the identity before opening Mongo, S3 or writing any file.
    identity = os.environ['BACKUP_ENCRYPTION_KEY'].encode() + b'\n'
    recipient = run(['age-keygen', '-y'], input=identity).decode().strip()
    s3 = boto3.client('s3', endpoint_url=os.environ.get('BACKUP_S3_ENDPOINT') or None,
                      region_name=os.environ.get('BACKUP_S3_REGION', 'eu-west-3'),
                      aws_access_key_id=os.environ['BACKUP_S3_ACCESS_KEY'],
                      aws_secret_access_key=os.environ['BACKUP_S3_SECRET_KEY'],
                      config=Config(signature_version='s3v4', s3={'addressing_style': 'path'},
                                    connect_timeout=10, read_timeout=60, retries={'max_attempts': 3}))
    return s3, os.environ['BACKUP_S3_BUCKET'], prefix, retention, identity, recipient


def counts(client):
    result = {}
    for database in sorted(client.list_database_names()):
        # local is replica machinery; config is transient sessions/transactions.
        # mongodump's restorable user data includes admin users/roles/version.
        if database in ('local', 'config'):
            continue
        for collection in client[database].list_collections():
            name = collection['name']
            if collection['type'] != 'collection' or name == 'system.profile':
                continue
            # Internal cluster-time keys are not readable by Mongo's backup role
            # or included by mongodump; the replacement replica generates them.
            if database == 'admin' and name == 'system.keys':
                continue
            try:
                result[f'{database}.{name}'] = client[database][name].count_documents({})
            except OperationFailure as error:
                raise Failure(f'Count failed for {database}.{name}; MongoDB code={error.code}') from None
    return dict(sorted(result.items()))


def objects(s3, bucket, prefix):
    found = []
    for page in s3.get_paginator('list_objects_v2').paginate(Bucket=bucket, Prefix=prefix):
        for item in page.get('Contents', []):
            suffix = item['Key'][len(prefix):]
            if re.fullmatch(r'\d{8}T\d{6}Z-[a-f0-9]{32}\.tar\.age', suffix):
                found.append(item)
    return found


def prune(s3, bucket, prefix, retention, current):
    cutoff = dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=retention)
    expired = [item['Key'] for item in objects(s3, bucket, prefix)
               if item['Key'] != current and item['LastModified'] < cutoff]
    for old in expired:
        s3.delete_object(Bucket=bucket, Key=old)
    return len(expired)


def backup(s3, bucket, prefix, retention, identity, recipient, work):
    # Check destination access before locking Mongo or producing a dump.
    s3.list_objects_v2(Bucket=bucket, Prefix=prefix, MaxKeys=1)
    source = MongoClient('mongodb://mongo:27017/?replicaSet=rs0&directConnection=true',
                         username=os.environ['MONGO_BACKUP_USERNAME'],
                         password=os.environ['MONGO_BACKUP_PASSWORD'], authSource='admin',
                         serverSelectionTimeoutMS=10000, socketTimeoutMS=60000)
    source.admin.command('ping')
    credentials = work / 'dump-config.json'
    credentials.write_text(json.dumps({'password': os.environ['MONGO_BACKUP_PASSWORD']}))
    dump = work / 'dump.archive.gz'
    locked = False
    try:
        # Count and dump the same snapshot. Never compare a later, changing live DB.
        source.admin.command({'fsync': 1, 'lock': True})
        locked = True
        manifest = {'format': 1, 'createdAt': dt.datetime.now(dt.timezone.utc).isoformat(),
                    'counts': counts(source), 'excludedDatabases': ['config', 'local']}
        run(['mongodump', '--host=mongo:27017', '--username=' + os.environ['MONGO_BACKUP_USERNAME'],
             '--authenticationDatabase=admin', '--config=' + str(credentials), '--oplog',
             '--gzip', '--archive=' + str(dump)])
        if manifest['counts'] != counts(source):
            raise Failure('Source changed while locked; refusing backup')
    finally:
        if locked:
            source.admin.command({'fsyncUnlock': 1})
        source.close()
    credentials.unlink()
    (work / 'manifest.json').write_text(json.dumps(manifest, sort_keys=True))
    archive = work / 'backup.tar'
    with tarfile.open(archive, 'w') as bundle:
        for filename in ('manifest.json', 'dump.archive.gz'):
            bundle.add(work / filename, arcname=filename)
    encrypted = work / 'backup.tar.age'
    run(['age', '-r', recipient, '-o', str(encrypted), str(archive)])
    key = prefix + dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ-') + uuid.uuid4().hex + '.tar.age'
    s3.upload_file(str(encrypted), bucket, key, ExtraArgs={'ContentType': 'application/octet-stream'})
    if s3.head_object(Bucket=bucket, Key=key)['ContentLength'] != encrypted.stat().st_size:
        raise Failure('Uploaded size mismatch; retention not run')
    # Delete only our named objects, only after a successful upload, never an unrelated prefix.
    deleted = prune(s3, bucket, prefix, retention, key)
    print(f'BACKUP_OK key={key} collections={len(manifest["counts"])} retentionDeleted={deleted}')


def restore(s3, bucket, prefix, retention, identity, recipient, work, named):
    available = objects(s3, bucket, prefix)
    if named:
        if named not in {item['Key'] for item in available}:
            raise Failure('Requested backup not found under the configured prefix')
        key = named
    elif available:
        key = max(available, key=lambda item: (item['LastModified'], item['Key']))['Key']
    else:
        raise Failure('No backup found under the configured prefix')
    encrypted = work / 'backup.tar.age'
    s3.download_file(bucket, key, str(encrypted))
    keyfile = work / 'identity'
    keyfile.write_bytes(identity)
    archive = work / 'backup.tar'
    # Authenticate the entire archive before extracting it or starting Mongo.
    run(['age', '-d', '-i', str(keyfile), '-o', str(archive), str(encrypted)])
    with tarfile.open(archive, 'r:') as bundle:
        members = bundle.getmembers()
        if sorted(m.name for m in members) != ['dump.archive.gz', 'manifest.json'] or not all(m.isfile() for m in members):
            raise Failure('Unexpected archive contents')
        for member in members:
            with bundle.extractfile(member) as source, (work / member.name).open('wb') as target:
                import shutil
                shutil.copyfileobj(source, target)
    manifest = json.loads((work / 'manifest.json').read_text())
    if manifest.get('format') != 1 or not isinstance(manifest.get('counts'), dict):
        raise Failure('Unsupported manifest')
    data = work / 'mongo'
    data.mkdir()
    with (work / 'mongo.log').open('wb') as logfile:
        # A fresh standalone in this disposable container, no port published and TTL disabled.
        mongo = subprocess.Popen(['mongod', '--dbpath', str(data), '--port', '27018', '--bind_ip', '127.0.0.1',
                                  '--setParameter', 'ttlMonitorEnabled=false'], stdout=logfile, stderr=logfile)
        client = MongoClient('mongodb://127.0.0.1:27018/?directConnection=true', serverSelectionTimeoutMS=1000)
        try:
            for attempt in range(60):
                try:
                    client.admin.command('ping')
                    break
                except Exception:
                    if mongo.poll() is not None:
                        raise Failure('Temporary Mongo failed to start') from None
                    time.sleep(0.5)
            else:
                raise Failure('Temporary Mongo did not become ready')
            run(['mongorestore', '--host=127.0.0.1:27018', '--archive=' + str(work / 'dump.archive.gz'),
                 '--gzip', '--oplogReplay', '--stopOnError'])
            restored = counts(client)
            for name in sorted(set(manifest['counts']) | set(restored)):
                expected, actual = manifest['counts'].get(name), restored.get(name)
                print(f'COUNT {name} source={expected} restored={actual}')
            if restored != manifest['counts']:
                raise Failure('Collection count mismatch')
            print(f'RESTORE_OK key={key} collections={len(restored)} sourceSnapshot={manifest["createdAt"]}')
        finally:
            client.close()
            mongo.terminate()
            try:
                mongo.wait(timeout=20)
            except subprocess.TimeoutExpired:
                mongo.kill()
                mongo.wait()


def main():
    os.umask(0o077)
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))
    options = sys.argv[1:]
    if options != ['backup'] and not (len(options) in (2, 3) and options[:2] == ['restore', '--verify']):
        raise Failure('Use backup or restore --verify [object-key]')
    config = configuration()
    with tempfile.TemporaryDirectory(prefix='mongo-', dir='/work') as directory:
        if options[0] == 'backup':
            backup(*config, Path(directory))
        else:
            restore(*config, Path(directory), options[2] if len(options) == 3 else None)


if __name__ == '__main__':
    try:
        main()
    except Failure as error:
        print(f'FAILED: {error}', file=sys.stderr)
        sys.exit(1)
    except OperationFailure as error:
        print(f'FAILED: MongoDB code={error.code}; private details suppressed', file=sys.stderr)
        sys.exit(1)
    except Exception as error:
        # SDK exceptions can contain URIs, keys or document contents. Never log their values.
        print(f'FAILED: {type(error).__name__}; provider/database details suppressed', file=sys.stderr)
        sys.exit(1)
