"""Streaming, public-key-only backup and isolated verification of a replica dump."""
import datetime as dt
import gzip
import json
import os
from pathlib import Path
import re
import shutil
import signal
import struct
import subprocess
import sys
import tarfile
import tempfile
import threading
import time
import uuid

import boto3
from boto3.s3.transfer import TransferConfig
from botocore.config import Config
from bson import BSON, Timestamp
from pymongo import MongoClient
from pymongo.errors import OperationFailure

GIB = 1024 ** 3
TRANSFER = TransferConfig(multipart_chunksize=8 * 1024 ** 2, max_concurrency=1, use_threads=False)


class Failure(Exception):
    pass


def run(command, **kwargs):
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=900, **kwargs)
    if result.returncode:
        raise Failure(f'{command[0]} failed (exit {result.returncode}); private tool output suppressed')
    return result.stdout


def configuration(mode):
    required = ['BACKUP_S3_BUCKET', 'BACKUP_S3_ACCESS_KEY', 'BACKUP_S3_SECRET_KEY']
    required += (['BACKUP_AGE_RECIPIENT', 'MONGO_BACKUP_USERNAME', 'MONGO_BACKUP_PASSWORD']
                 if mode == 'backup' else ['BACKUP_AGE_IDENTITY'])
    for key in required:
        if not os.environ.get(key):
            raise Failure(f'Missing {key}')
    prefix = os.environ.get('BACKUP_S3_PREFIX', 'mongo/')
    if not re.fullmatch(r'[A-Za-z0-9_/-]+/', prefix) or '..' in prefix or prefix.startswith('/'):
        raise Failure('Invalid BACKUP_S3_PREFIX')
    recipient = os.environ.get('BACKUP_AGE_RECIPIENT', '')
    identity = (os.environ.get('BACKUP_AGE_IDENTITY', '') + '\n').encode()
    if mode == 'backup':
        # Validate even the checksum, without writing any file or contacting Mongo/S3.
        run(['age', '-r', recipient], input=b'')
    else:
        run(['age-keygen', '-y'], input=identity)
    s3 = boto3.client('s3', endpoint_url=os.environ.get('BACKUP_S3_ENDPOINT') or None,
                      region_name=os.environ.get('BACKUP_S3_REGION', 'eu-west-3'),
                      aws_access_key_id=os.environ['BACKUP_S3_ACCESS_KEY'],
                      aws_secret_access_key=os.environ['BACKUP_S3_SECRET_KEY'],
                      config=Config(signature_version='s3v4', s3={'addressing_style': 'path'},
                                    connect_timeout=10, read_timeout=60, retries={'max_attempts': 3}))
    return s3, os.environ['BACKUP_S3_BUCKET'], prefix, recipient, identity


def inventory(client):
    result = []
    for database in sorted(client.list_database_names()):
        if database in ('local', 'config'):
            continue
        for collection in client[database].list_collections():
            name = collection['name']
            if collection['type'] != 'collection' or name in ('system.profile', 'system.views'):
                continue
            if database == 'admin' and name == 'system.keys':
                continue
            # listCollections is not a snapshot read. Retain UUID/options metadata
            # so a concurrent DDL change invalidates this attempt, never a false proof.
            result.append((database, name, collection))
    return sorted(result, key=lambda row: row[:2])


def counts(client, catalog=None, timestamp=None):
    result = {}
    for database, name, _ in catalog if catalog is not None else inventory(client):
        if timestamp is None:
            result[f'{database}.{name}'] = client[database][name].count_documents({})
        else:
            response = client[database].command({
                'aggregate': name, 'pipeline': [{'$count': 'n'}], 'cursor': {},
                'readConcern': {'level': 'snapshot', 'atClusterTime': timestamp}, 'maxTimeMS': 60000})
            rows = response['cursor']['firstBatch']
            result[f'{database}.{name}'] = rows[0]['n'] if rows else 0
    return dict(sorted(result.items()))


def check_space(work, estimate):
    if estimate < GIB or shutil.disk_usage(work).free < estimate:
        raise Failure('Insufficient private work disk space for the estimated backup/restore')


def estimate_space(client):
    total = 0
    # Mongo's built-in backup role permits collStats, but not dbStats.
    for database, name, _ in inventory(client):
        stats = client[database].command('collStats', name)
        total += stats['size'] + stats['totalIndexSize']
    # Up to two ciphertext copies for backup; restore has archive plus a database.
    # Reserve growth/compression uncertainty and Mongo journal/preallocation space.
    return max(GIB, int(total * 4 + GIB))


def copy_archive(source, target):
    """Observe archive 0.1 framing while forwarding bytes, buffering at most one BSON.

    Mongo tools' common/archive format: magic, prelude block, namespace blocks.
    Only namespace headers and captured oplog documents are decoded. No user data
    is logged or retained. The inclusive captured oplog ends at dump.oplogEnd.
    """
    def read(size, eof=False):
        value = source.read(size)
        if eof and not value:
            return value
        if len(value) != size:
            raise Failure('Truncated mongodump archive')
        target.write(value)
        return value

    if read(4) != struct.pack('<I', 0x8199e26d):
        raise Failure('Unsupported mongodump archive')
    prelude = True
    last = None
    namespaces = set()
    while True:
        raw = read(4, eof=True)
        if not raw:
            break
        size = struct.unpack('<i', raw)[0]
        if not 5 <= size <= 16 * 1024 ** 2 + 16384:
            raise Failure('Invalid archive header size')
        header = BSON(raw + read(size - 4)).decode()
        if prelude and header.get('version') != '0.1':
            raise Failure('Unsupported archive version')
        oplog = not prelude and header.get('db') == '' and header.get('collection') == 'oplog'
        while True:
            raw = read(4)
            size = struct.unpack('<i', raw)[0]
            if size == -1:
                break
            if not 5 <= size <= 16 * 1024 ** 2 + 16384:
                raise Failure('Invalid archive document size')
            doc = raw + read(size - 4)
            if prelude:
                meta = BSON(doc).decode()
                if meta.get('db') not in ('', 'local', 'config') and meta.get('type') != 'view':
                    namespaces.add(meta['db'] + '.' + meta['collection'])
            if oplog:
                stamp = BSON(doc).decode().get('ts')
                if not isinstance(stamp, Timestamp) or (last is not None and stamp < last):
                    raise Failure('Invalid captured oplog timestamp')
                last = stamp
        prelude = False
    if last is None:
        # The pinned tool includes the start entry even on an idle replica (gte/lte).
        raise Failure('Missing captured oplog end timestamp')
    return last, namespaces


def stream_dump(work, recipient):
    credentials = work / 'dump-config.json'
    credentials.write_text(json.dumps({'password': os.environ['MONGO_BACKUP_PASSWORD']}))
    encrypted = work / 'dump.archive.gz.age'
    dump = subprocess.Popen(['mongodump', '--host=mongo:27017',
                             '--username=' + os.environ['MONGO_BACKUP_USERNAME'],
                             '--authenticationDatabase=admin', '--config=' + str(credentials),
                             '--oplog', '--archive'], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    encrypt = None
    timer = None
    try:
        encrypt = subprocess.Popen(['age', '-r', recipient, '-o', str(encrypted)],
                                   stdin=subprocess.PIPE, stderr=subprocess.DEVNULL)
        def expire():
            for process in (dump, encrypt):
                if process.poll() is None:
                    process.kill()
        timer = threading.Timer(900, expire)
        timer.start()
        with gzip.GzipFile(fileobj=encrypt.stdin, mode='wb', mtime=0) as compressed:
            stamp, namespaces = copy_archive(dump.stdout, compressed)
        encrypt.stdin.close()
        dump.stdout.close()
        if dump.wait(timeout=20) or encrypt.wait(timeout=20):
            raise Failure('Dump/encryption pipeline failed; nothing uploaded')
        return stamp, namespaces
    finally:
        if timer:
            timer.cancel()
        for process in (dump, encrypt):
            if process is not None:
                if process.poll() is None:
                    process.kill()
                process.wait()
                for pipe in (process.stdin, process.stdout):
                    if pipe and not pipe.closed:
                        pipe.close()
        credentials.unlink(missing_ok=True)


def objects(s3, bucket, prefix):
    found = []
    for page in s3.get_paginator('list_objects_v2').paginate(Bucket=bucket, Prefix=prefix):
        for item in page.get('Contents', []):
            if not item['Key'].startswith(prefix):
                continue
            suffix = item['Key'][len(prefix):]
            if re.fullmatch(r'\d{8}T\d{6}Z-[a-f0-9]{32}\.tar', suffix):
                found.append(item)
    return found


def backup(s3, bucket, prefix, recipient, identity, work):
    s3.list_objects_v2(Bucket=bucket, Prefix=prefix, MaxKeys=1)
    source = MongoClient('mongodb://mongo:27017/?replicaSet=rs0&directConnection=true',
                         username=os.environ['MONGO_BACKUP_USERNAME'],
                         password=os.environ['MONGO_BACKUP_PASSWORD'], authSource='admin',
                         serverSelectionTimeoutMS=10000, socketTimeoutMS=65000)
    try:
        source.admin.command('ping')
        estimate = estimate_space(source)
        check_space(work, estimate)
        before = inventory(source)
        stamp, namespaces = stream_dump(work, recipient)
        if namespaces != {db + '.' + name for db, name, _ in before}:
            raise Failure('Dump collection set differs from source catalog')
        manifest = {'format': 2, 'createdAt': dt.datetime.now(dt.timezone.utc).isoformat(),
                    'atClusterTime': [stamp.time, stamp.inc], 'workBytes': estimate,
                    'counts': counts(source, before, stamp)}
        if before != inventory(source):
            raise Failure('Source catalog changed during backup; retry without DDL')
    finally:
        source.close()
    run(['age', '-r', recipient, '-o', str(work / 'manifest.json.age')],
        input=json.dumps(manifest, sort_keys=True).encode())
    archive = work / 'backup.tar'
    with tarfile.open(archive, 'w') as bundle:
        for name in ('manifest.json.age', 'dump.archive.gz.age'):
            bundle.add(work / name, arcname=name)
    for name in ('manifest.json.age', 'dump.archive.gz.age'):
        (work / name).unlink()
    key = prefix + dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ-') + uuid.uuid4().hex + '.tar'
    # The cron principal has only PutObject and ListBucket, no read or deletion.
    s3.upload_file(str(archive), bucket, key, ExtraArgs={
        'ContentType': 'application/x-tar', 'Metadata': {'work-bytes': str(estimate)}}, Config=TRANSFER)
    print(f'BACKUP_OK key={key} collections={len(manifest["counts"])} snapshot={stamp.time}:{stamp.inc} retention=S3-lifecycle')
    return key


def restore(s3, bucket, prefix, recipient, identity, work, named):
    available = objects(s3, bucket, prefix)
    if named:
        if named not in {item['Key'] for item in available}:
            raise Failure('Requested backup not found under the configured prefix')
        key = named
    elif available:
        key = max(available, key=lambda item: (item['LastModified'], item['Key']))['Key']
    else:
        raise Failure('No backup found under the configured prefix')
    info = s3.head_object(Bucket=bucket, Key=key)
    estimate = int(info.get('Metadata', {}).get('work-bytes', '0'))
    check_space(work, max(estimate, info['ContentLength'] * 3))
    archive = work / 'backup.tar'
    s3.download_file(bucket, key, str(archive), Config=TRANSFER)
    with tarfile.open(archive, 'r:') as bundle:
        members = bundle.getmembers()
        if sorted(m.name for m in members) != ['dump.archive.gz.age', 'manifest.json.age'] or not all(m.isfile() for m in members):
            raise Failure('Unexpected archive contents')
        for member in members:
            with bundle.extractfile(member) as source, (work / member.name).open('wb') as target:
                shutil.copyfileobj(source, target, 1024 * 1024)
    archive.unlink()
    keyfile = work / 'identity'
    keyfile.write_bytes(identity)
    manifest = json.loads(run(['age', '-d', '-i', str(keyfile), str(work / 'manifest.json.age')]))
    if manifest.get('format') != 2 or not isinstance(manifest.get('counts'), dict):
        raise Failure('Unsupported manifest')
    check_space(work, manifest['workBytes'])
    # Authenticate all ciphertext before starting the isolated database.
    run(['age', '-d', '-i', str(keyfile), '-o', str(work / 'dump.archive.gz'), str(work / 'dump.archive.gz.age')])
    for name in ('identity', 'manifest.json.age', 'dump.archive.gz.age'):
        (work / name).unlink()
    data = work / 'mongo'
    data.mkdir()
    with (work / 'mongo.log').open('wb') as logfile:
        mongo = subprocess.Popen(['mongod', '--dbpath', str(data), '--port', '27018', '--bind_ip', '127.0.0.1',
                                  '--wiredTigerCacheSizeGB', '0.25', '--setParameter', 'ttlMonitorEnabled=false'],
                                 stdout=logfile, stderr=logfile)
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
                print(f'COUNT {name} source={manifest["counts"].get(name)} restored={restored.get(name)}')
            if restored != manifest['counts']:
                raise Failure('Collection count mismatch')
            print(f'RESTORE_OK key={key} collections={len(restored)} atClusterTime={manifest["atClusterTime"]}')
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
    config = configuration(options[0])
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
        print(f'FAILED: {type(error).__name__}; provider/database details suppressed', file=sys.stderr)
        sys.exit(1)
