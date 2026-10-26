"""Streaming, public-key-only backup and isolated verification of a replica dump."""
import datetime as dt
import gzip
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import signal
import struct
import stat
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



def private_directory(value, *, marker=False):
    path = Path(value)
    if not path.is_absolute() or path == Path('/') or any(part == '..' for part in path.parts):
        raise Failure('Private storage directory must be absolute')
    if any(part.is_symlink() for part in (path, *path.parents)) or not path.is_dir():
        raise Failure('Private storage directory is absent or contains a symlink')
    if stat.S_IMODE(path.stat().st_mode) & 0o077:
        raise Failure('Private storage directory must deny group and other access')
    if marker:
        check = path / '.clubs-private-storage'
        if check.is_symlink() or not check.is_file() or stat.S_IMODE(check.stat().st_mode) & 0o077 \
                or check.read_bytes().strip() != b'clubs-private-storage-v1':
            raise Failure('Private application storage marker is missing or invalid')
    return path


def relative_file(value):
    if not isinstance(value, str) or not value or len(value) > 4096 or '\\' in value \
            or any(ord(c) < 32 or ord(c) == 127 for c in value):
        raise Failure('Invalid archive path')
    path = PurePosixPath(value)
    if path.is_absolute() or str(path) != value or any(part in ('.', '..') for part in path.parts):
        raise Failure('Invalid archive path')
    return path


def file_inventory(root):
    result = {}
    for directory, names, files in os.walk(root, followlinks=False):
        for name in sorted(names + files):
            path = Path(directory) / name
            key = path.relative_to(root).as_posix()
            relative_file(key)
            info = path.lstat()
            if stat.S_ISDIR(info.st_mode):
                result[key] = {'type': 'directory'}
            elif stat.S_ISREG(info.st_mode) and info.st_nlink == 1:
                digest = hashlib.sha256()
                with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW), 'rb') as source:
                    opened = os.fstat(source.fileno())
                    if (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns) != \
                            (opened.st_dev, opened.st_ino, opened.st_size, opened.st_mtime_ns):
                        raise Failure('Application storage changed during backup')
                    for chunk in iter(lambda: source.read(1024 * 1024), b''):
                        digest.update(chunk)
                    after = os.fstat(source.fileno())
                    if (opened.st_size, opened.st_mtime_ns, opened.st_ctime_ns) != \
                            (after.st_size, after.st_mtime_ns, after.st_ctime_ns):
                        raise Failure('Application storage changed during backup')
                result[key] = {'type': 'file', 'size': info.st_size, 'sha256': digest.hexdigest()}
            else:
                raise Failure('Application storage must contain only regular files and directories')
    return dict(sorted(result.items()))


def encrypt_files(root, expected, recipient, target):
    process = subprocess.Popen(['age', '-r', recipient, '-o', str(target)], stdin=subprocess.PIPE,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        with tarfile.open(fileobj=process.stdin, mode='w|') as bundle:
            for key, metadata in expected.items():
                path = root.joinpath(*relative_file(key).parts)
                info = tarfile.TarInfo(key)
                info.mode = 0o700 if metadata['type'] == 'directory' else 0o600
                if metadata['type'] == 'directory':
                    if not path.is_dir() or path.is_symlink():
                        raise Failure('Application storage changed during backup')
                    info.type = tarfile.DIRTYPE
                    bundle.addfile(info)
                else:
                    info.size = metadata['size']
                    with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW), 'rb') as source:
                        current = os.fstat(source.fileno())
                        if not stat.S_ISREG(current.st_mode) or current.st_nlink != 1 or current.st_size != info.size:
                            raise Failure('Application storage changed during backup')
                        bundle.addfile(info, source)
        process.stdin.close()
        if process.wait(timeout=900):
            raise Failure('Application file encryption failed')
        if file_inventory(root) != expected:
            raise Failure('Application storage changed during backup')
    finally:
        if process.poll() is None:
            process.kill()
        process.wait()
        if process.stdin and not process.stdin.closed:
            process.stdin.close()


def verify_files(archive, expected, output):
    if not isinstance(expected, dict):
        raise Failure('Invalid application file manifest')
    output.mkdir(mode=0o700)
    seen = set()
    with tarfile.open(archive, 'r:') as bundle:
        for member in bundle:
            path = relative_file(member.name)
            if member.name in seen or member.name not in expected:
                raise Failure('Unexpected or duplicate application file entry')
            seen.add(member.name)
            metadata = expected[member.name]
            target = output.joinpath(*path.parts)
            target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            if metadata == {'type': 'directory'} and member.isdir():
                target.mkdir(mode=0o700, exist_ok=True)
            elif isinstance(metadata, dict) and set(metadata) == {'type', 'size', 'sha256'} \
                    and metadata['type'] == 'file' and type(metadata['size']) is int and metadata['size'] >= 0 \
                    and re.fullmatch(r'[a-f0-9]{64}', str(metadata['sha256'])) \
                    and member.isfile() and member.sparse is None and member.size == metadata['size']:
                digest = hashlib.sha256()
                with bundle.extractfile(member) as source, target.open('xb') as destination:
                    for chunk in iter(lambda: source.read(1024 * 1024), b''):
                        digest.update(chunk)
                        destination.write(chunk)
                target.chmod(0o600)
                if digest.hexdigest() != metadata['sha256']:
                    raise Failure('Application file checksum mismatch')
            else:
                raise Failure('Application file type or size differs from manifest')
    if seen != set(expected) or file_inventory(output) != expected:
        raise Failure('Application file inventory mismatch')


class FilesystemStore:
    """Narrow object-store interface; only encrypted immutable archives reach this directory."""
    def __init__(self, root, files):
        self.root = root
        self.files = files

    def _path(self, key):
        path = relative_file(key)
        if len(path.parts) != 2 or path.parts[0] != 'mongo' or not re.fullmatch(r'\d{8}T\d{6}Z-[a-f0-9]{32}\.tar', path.name):
            raise Failure('Invalid filesystem backup key')
        target = self.root.joinpath(*path.parts)
        if target.parent.is_symlink() or target.is_symlink():
            raise Failure('Filesystem backup symlink is forbidden')
        return target

    def list_objects_v2(self, *, Bucket, Prefix, MaxKeys=None):
        if Prefix != 'mongo/':
            raise Failure('Filesystem backup prefix must be mongo/')
        directory = self.root / Prefix
        if directory.is_symlink():
            raise Failure('Filesystem backup symlink is forbidden')
        rows = []
        if directory.exists():
            for path in sorted(directory.iterdir()):
                if path.suffix == '.tar':
                    target = self._path(Prefix + path.name)
                    if not target.is_file() or target.stat().st_nlink != 1:
                        raise Failure('Invalid filesystem backup file')
                    rows.append({'Key': Prefix + path.name, 'LastModified': dt.datetime.fromtimestamp(target.stat().st_mtime, dt.timezone.utc)})
        return {'Contents': rows[:MaxKeys] if MaxKeys is not None else rows}

    def get_paginator(self, operation):
        if operation != 'list_objects_v2':
            raise Failure('Unsupported filesystem operation')
        class Pages:
            def paginate(inner, **kwargs):
                yield self.list_objects_v2(**kwargs)
        return Pages()

    def upload_file(self, source, bucket, key, *, ExtraArgs, Config):
        target = self._path(key)
        target.parent.mkdir(mode=0o700, exist_ok=True)
        check_space(target.parent, max(GIB, Path(source).stat().st_size * 2))
        temporary = target.parent / ('.' + uuid.uuid4().hex + '.tmp')
        try:
            with open(source, 'rb') as input_file, temporary.open('xb') as output_file:
                shutil.copyfileobj(input_file, output_file, 1024 * 1024)
                output_file.flush()
                os.fsync(output_file.fileno())
            temporary.chmod(0o600)
            # link() refuses an existing archive; readers never observe partial ciphertext.
            os.link(temporary, target)
            descriptor = os.open(target.parent, os.O_RDONLY)
            try:
                os.fsync(descriptor)
            finally:
                os.close(descriptor)
        finally:
            temporary.unlink(missing_ok=True)

    def head_object(self, *, Bucket, Key):
        target = self._path(Key)
        if not target.is_file() or target.stat().st_nlink != 1:
            raise Failure('Invalid filesystem backup file')
        return {'ContentLength': target.stat().st_size, 'Metadata': {}}

    def download_file(self, bucket, key, destination, *, Config):
        source = self._path(key)
        with os.fdopen(os.open(source, os.O_RDONLY | os.O_NOFOLLOW), 'rb') as input_file, open(destination, 'xb') as output_file:
            info = os.fstat(input_file.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
                raise Failure('Invalid filesystem backup file')
            shutil.copyfileobj(input_file, output_file, 1024 * 1024)

def configuration(mode):
    backend = os.environ.get('BACKUP_STORAGE_MODE', 's3')
    if backend not in ('s3', 'filesystem'):
        raise Failure('Invalid BACKUP_STORAGE_MODE')
    local = backend == 'filesystem'
    if local and (os.environ.get('HOSTED_DEMO_ENABLED') != 'true' or os.environ.get('FILE_STORAGE_MODE') != 'filesystem'):
        raise Failure('Filesystem backups require explicit internal filesystem policy')
    required = ['BACKUP_LOCAL_DIRECTORY', 'BACKUP_FILES_DIRECTORY'] if local else ['BACKUP_S3_BUCKET', 'BACKUP_S3_ACCESS_KEY', 'BACKUP_S3_SECRET_KEY']
    required += (['BACKUP_AGE_RECIPIENT', 'MONGO_BACKUP_USERNAME', 'MONGO_BACKUP_PASSWORD']
                 if mode == 'backup' else ['BACKUP_AGE_IDENTITY'])
    for key in required:
        if not os.environ.get(key):
            raise Failure(f'Missing {key}')
    prefix = 'mongo/' if local else os.environ.get('BACKUP_S3_PREFIX', 'mongo/')
    if not re.fullmatch(r'[A-Za-z0-9_/-]+/', prefix) or '..' in prefix or prefix.startswith('/'):
        raise Failure('Invalid BACKUP_S3_PREFIX')
    recipient = os.environ.get('BACKUP_AGE_RECIPIENT', '')
    identity = (os.environ.get('BACKUP_AGE_IDENTITY', '') + '\n').encode()
    if mode == 'backup':
        # Validate even the checksum, without writing any file or contacting Mongo/S3.
        run(['age', '-r', recipient], input=b'')
    else:
        run(['age-keygen', '-y'], input=identity)
    if local:
        if prefix != 'mongo/':
            raise Failure('Filesystem backup prefix must be mongo/')
        archive_root = private_directory(os.environ['BACKUP_LOCAL_DIRECTORY'])
        files = private_directory(os.environ['BACKUP_FILES_DIRECTORY'], marker=True)
        if archive_root == files or archive_root.is_relative_to(files) or files.is_relative_to(archive_root):
            raise Failure('Backup and application directories must be separate')
        if mode == 'backup' and os.environ.get('BACKUP_FILES_QUIESCED') != 'true':
            raise Failure('Stop application writers before filesystem backup')
        return FilesystemStore(archive_root, files), 'filesystem', prefix, recipient, identity
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
    local = isinstance(s3, FilesystemStore)
    app_files = file_inventory(s3.files) if local else None
    source = MongoClient('mongodb://mongo:27017/?replicaSet=rs0&directConnection=true',
                         username=os.environ['MONGO_BACKUP_USERNAME'],
                         password=os.environ['MONGO_BACKUP_PASSWORD'], authSource='admin',
                         serverSelectionTimeoutMS=10000, socketTimeoutMS=65000)
    try:
        source.admin.command('ping')
        estimate = estimate_space(source)
        if local:
            estimate += 4 * sum(row.get('size', 0) for row in app_files.values())
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
    entries = ['manifest.json.age', 'dump.archive.gz.age']
    if local:
        encrypt_files(s3.files, app_files, recipient, work / 'files.tar.age')
        manifest.update(format=3, files=app_files)
        entries.append('files.tar.age')
    run(['age', '-r', recipient, '-o', str(work / 'manifest.json.age')],
        input=json.dumps(manifest, sort_keys=True).encode())
    archive = work / 'backup.tar'
    with tarfile.open(archive, 'w') as bundle:
        for name in entries:
            bundle.add(work / name, arcname=name)
    for name in entries:
        (work / name).unlink()
    key = prefix + dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ-') + uuid.uuid4().hex + '.tar'
    # The cron principal has only PutObject and ListBucket, no read or deletion.
    s3.upload_file(str(archive), bucket, key, ExtraArgs={
        'ContentType': 'application/x-tar', 'Metadata': {'work-bytes': str(estimate)}}, Config=TRANSFER)
    retention = 'operator-offhost' if local else 'S3-lifecycle'
    print(f'BACKUP_OK key={key} collections={len(manifest["counts"])} snapshot={stamp.time}:{stamp.inc} retention={retention}')
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
    check_space(work, max(GIB, estimate, info['ContentLength'] * 3))
    archive = work / 'backup.tar'
    s3.download_file(bucket, key, str(archive), Config=TRANSFER)
    with tarfile.open(archive, 'r:') as bundle:
        members = bundle.getmembers()
        expected_entries = ['dump.archive.gz.age', 'files.tar.age', 'manifest.json.age'] if isinstance(s3, FilesystemStore) else ['dump.archive.gz.age', 'manifest.json.age']
        if sorted(m.name for m in members) != expected_entries or not all(m.isfile() and m.sparse is None for m in members):
            raise Failure('Unexpected archive contents')
        for member in members:
            with bundle.extractfile(member) as source, (work / member.name).open('wb') as target:
                shutil.copyfileobj(source, target, 1024 * 1024)
    archive.unlink()
    keyfile = work / 'identity'
    keyfile.write_bytes(identity)
    manifest = json.loads(run(['age', '-d', '-i', str(keyfile), str(work / 'manifest.json.age')]))
    expected_format = 3 if isinstance(s3, FilesystemStore) else 2
    if manifest.get('format') != expected_format or not isinstance(manifest.get('counts'), dict):
        raise Failure('Unsupported manifest')
    check_space(work, manifest['workBytes'])
    # Authenticate all ciphertext before starting the isolated database.
    run(['age', '-d', '-i', str(keyfile), '-o', str(work / 'dump.archive.gz'), str(work / 'dump.archive.gz.age')])
    if expected_format == 3:
        run(['age', '-d', '-i', str(keyfile), '-o', str(work / 'files.tar'), str(work / 'files.tar.age')])
        verify_files(work / 'files.tar', manifest.get('files'), work / 'restored-files')
        (work / 'files.tar').unlink()
        (work / 'files.tar.age').unlink()
        print(f'FILES_OK entries={len(manifest["files"])}')
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
