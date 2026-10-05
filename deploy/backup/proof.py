"""Real Mongo/MinIO proofs, only on local-proof.py's disposable fictional stack."""
import io
import json
import os
from pathlib import Path
import sys
import tarfile
import tempfile
import time
from unittest.mock import patch

from pymongo import MongoClient
from pymongo.errors import OperationFailure

import backup

def main():
    s3, bucket, prefix, recipient, identity = backup.configuration('restore')
    key, bad_key, mismatch_key = sys.argv[1:]
    with tempfile.TemporaryDirectory(dir='/work') as directory:
        work = Path(directory)
        keyfile = work / 'key'
        keyfile.write_bytes(identity)
        original = work / 'original.tar'
        s3.download_file(bucket, key, str(original))
        metadata = s3.head_object(Bucket=bucket, Key=key)['Metadata']
        with tarfile.open(original) as bundle:
            encrypted_manifest = bundle.extractfile('manifest.json.age').read()
            encrypted_dump = bundle.extractfile('dump.archive.gz.age').read()
        manifest = json.loads(backup.run(['age', '-d', '-i', str(keyfile)], input=encrypted_manifest))
        collection = next(k for k in manifest['counts'] if k.startswith('agilityhub.'))
        manifest['counts'][collection] += 1
        wrong = backup.run(['age', '-r', recipient], input=json.dumps(manifest).encode())
        broken = bytearray(encrypted_dump)
        broken[-1] ^= 1
        for name, data, manifest_bytes in [(bad_key, bytes(broken), encrypted_manifest),
                                           (mismatch_key, encrypted_dump, wrong)]:
            with tarfile.open(work / 'changed.tar', 'w') as bundle:
                for member_name, content in [('manifest.json.age', manifest_bytes), ('dump.archive.gz.age', data)]:
                    member = tarfile.TarInfo(member_name)
                    member.size = len(content)
                    bundle.addfile(member, io.BytesIO(content))
            s3.upload_file(str(work / 'changed.tar'), bucket, name, ExtraArgs={'Metadata': metadata})

    app = MongoClient(os.environ['LOCAL_MONGODB_URI'], timeoutMS=2000)
    source = MongoClient('mongodb://mongo:27017/?replicaSet=rs0&directConnection=true',
                         username=os.environ['MONGO_BACKUP_USERNAME'], password=os.environ['MONGO_BACKUP_PASSWORD'],
                         authSource='admin', timeoutMS=2000)
    try:
        roles = source.admin.command('connectionStatus')['authInfo']['authenticatedUserRoles']
        assert roles == [{'role': 'backup', 'db': 'admin'}], 'Backup must have only the backup role'
        try:
            source.agilityhub.backup_probe.insert_one({'_id': 'forbidden'})
            raise AssertionError('Backup principal can write')
        except OperationFailure as error:
            assert error.code == 13
        print('PASS backup principal has only backup role and cannot write', flush=True)
        app.agilityhub.backup_probe.insert_many([
            {'_id': 'payload', 'bytes': os.urandom(4 * 1024 * 1024)}, {'_id': 'deleted'}, {'_id': 'updated', 'value': 0}])
        original_copy = backup.copy_archive
        original_counts = backup.counts
        during = []

        def concurrent_copy(stream, encrypted):
            # Reading a header proves the real mongodump has started. The 4 MiB row
            # cannot fit into the pipe, so the dump cannot finish while we hold it.
            first = stream.read(4)
            started = time.monotonic()
            with app.start_session() as session:
                with session.start_transaction():
                    app.agilityhub.backup_probe.insert_one({'_id': 'during'}, session=session)
                    app.agilityhub.backup_probe.delete_one({'_id': 'deleted'}, session=session)
                    app.agilityhub.backup_probe.update_one({'_id': 'updated'}, {'$set': {'value': 1}}, session=session)
            elapsed = time.monotonic() - started
            assert elapsed < 2, 'Write waited for backup completion'
            during.append(elapsed)
            class Replay:
                def read(self, size):
                    nonlocal first
                    if first:
                        value, first = first, b''
                        return value
                    return stream.read(size)
            return original_copy(Replay(), encrypted)

        def later_write(client, catalog=None, timestamp=None):
            if timestamp is not None:
                app.agilityhub.backup_probe.insert_one({'_id': 'after-dump'})
            result = original_counts(client, catalog, timestamp)
            if timestamp is not None:
                assert result['agilityhub.backup_probe'] == 3
                assert app.agilityhub.backup_probe.count_documents({}) == 4
                print('PASS snapshot counts exclude the later live write (source snapshot=3, live=4)', flush=True)
            return result

        with tempfile.TemporaryDirectory(dir='/work') as directory, \
                patch.object(backup, 'copy_archive', side_effect=concurrent_copy), \
                patch.object(backup, 'counts', side_effect=later_write):
            concurrent_key = backup.backup(s3, bucket, prefix, recipient, b'', Path(directory))
        assert during
        print(f'PASS transaction insert/update/delete completed DURING mongodump in {during[0]:.3f}s', flush=True)
        with tempfile.TemporaryDirectory(dir='/work') as directory:
            backup.restore(s3, bucket, prefix, recipient, identity, Path(directory), concurrent_key)
        print('PASS concurrent-write backup restores exact snapshot counts', flush=True)
        with tempfile.TemporaryDirectory(dir='/work') as directory, \
                patch.object(backup.shutil, 'disk_usage', return_value=type('Usage', (), {'free': 0})()), \
                patch.object(backup, 'stream_dump') as dump:
            try:
                backup.backup(s3, bucket, prefix, recipient, b'', Path(directory))
                raise AssertionError('Low disk accepted')
            except backup.Failure as error:
                assert 'Insufficient' in str(error)
            dump.assert_not_called()
            assert not list(Path(directory).iterdir())
        print('PASS low disk refuses before producing any dump', flush=True)
    finally:
        app.close()
        source.close()


if __name__ == '__main__':
    try:
        main()
    except (backup.Failure, AssertionError) as error:
        print(f'PROOF_FAILED: {error}', file=sys.stderr)
        sys.exit(1)
    except OperationFailure as error:
        print(f'PROOF_FAILED: MongoDB code={error.code}; private details suppressed', file=sys.stderr)
        sys.exit(1)
    except Exception as error:
        print(f'PROOF_FAILED: {type(error).__name__}; private details suppressed', file=sys.stderr)
        sys.exit(1)
