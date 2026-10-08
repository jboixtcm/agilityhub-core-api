"""Failure paths plus archive framing and real-shaped S3 responses."""
import datetime as dt
import io
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import MagicMock, patch

import boto3
from bson import BSON, Timestamp
from botocore.stub import Stubber
from pymongo.errors import OperationFailure
import backup


class BackupSafetyTest(unittest.TestCase):
    def test_E11_T04_size_estimate_uses_backup_role_collection_stats(self):
        client = MagicMock()
        client.list_database_names.return_value = ['fictional']
        def command(name, *args):
            if name == 'dbStats':
                raise OperationFailure('not authorized', code=13)
            self.assertEqual(('collStats', 'rows'), (name, *args))
            return {'size': 512, 'totalIndexSize': 1024, 'ok': 1.0}
        client.__getitem__.return_value.command.side_effect = command
        with patch.object(backup, 'inventory', return_value=[('fictional', 'rows', {})]):
            self.assertEqual(backup.GIB + 4 * 1536, backup.estimate_space(client))

    def test_E11_T04_snapshot_counts_all_collections_at_one_timestamp(self):
        client = MagicMock()
        client.__getitem__.return_value.command.side_effect = [
            {'cursor': {'id': 0, 'ns': 'fictional.rows', 'firstBatch': [{'n': 7}]}},
            {'cursor': {'id': 0, 'ns': 'fictional.empty', 'firstBatch': []}}]
        stamp = Timestamp(100, 7)
        self.assertEqual({'fictional.rows': 7, 'fictional.empty': 0}, backup.counts(
            client, [('fictional', 'rows', {}), ('fictional', 'empty', {})], stamp))
        for call in client.__getitem__.return_value.command.call_args_list:
            self.assertEqual({'level': 'snapshot', 'atClusterTime': stamp}, call.args[0]['readConcern'])

    def test_E11_T04_inventory_excludes_internal_cluster_keys(self):
        client = MagicMock()
        client.list_database_names.return_value = ['local', 'admin', 'config', 'fictional']
        admin, app = MagicMock(), MagicMock()
        client.__getitem__.side_effect = {'admin': admin, 'fictional': app}.__getitem__
        admin.list_collections.return_value = [
            {'name': 'system.keys', 'type': 'collection'}, {'name': 'system.users', 'type': 'collection'}]
        app.list_collections.return_value = [
            {'name': 'empty', 'type': 'collection'}, {'name': 'view', 'type': 'view'},
            {'name': 'system.views', 'type': 'collection'}, {'name': 'system.profile', 'type': 'collection'}]
        self.assertEqual([('admin', 'system.users'), ('fictional', 'empty')], [r[:2] for r in backup.inventory(client)])

    def test_E11_T04_archive_stream_tracks_only_the_actual_oplog(self):
        def block(header, *docs):
            return BSON.encode(header) + b''.join(BSON.encode(d) for d in docs) + b'\xff' * 4
        raw = struct.pack('<I', 0x8199e26d)
        raw += block({'version': '0.1'}, {'db': 'fictional', 'collection': 'rows', 'type': 'collection'})
        raw += block({'db': 'fictional', 'collection': 'rows'}, {'ts': Timestamp(999, 1)})
        raw += block({'db': '', 'collection': 'oplog'}, {'ts': Timestamp(100, 1)}, {'ts': Timestamp(100, 2)})
        out = io.BytesIO()
        self.assertEqual((Timestamp(100, 2), {'fictional.rows'}), backup.copy_archive(io.BytesIO(raw), out))
        self.assertEqual(raw, out.getvalue())
        with self.assertRaises(backup.Failure):
            backup.copy_archive(io.BytesIO(raw[:-1]), io.BytesIO())

    def test_E11_T04_prefix_filters_outside_keys_among_valid_pages(self):
        s3 = boto3.client('s3', region_name='us-east-1', aws_access_key_id='fictional', aws_secret_access_key='fictional')
        key = '20000101T000000Z-' + 'a' * 32 + '.tar'
        now = dt.datetime.now(dt.timezone.utc)
        with Stubber(s3) as stub:
            stub.add_response('list_objects_v2', {'IsTruncated': True, 'NextContinuationToken': 'next', 'Contents': [
                {'Key': 'mongo/' + key, 'LastModified': now}, {'Key': 'other/' + key, 'LastModified': now}]},
                {'Bucket': 'fictional', 'Prefix': 'mongo/'})
            stub.add_response('list_objects_v2', {'IsTruncated': False, 'Contents': [
                {'Key': 'mongo/nested/' + key, 'LastModified': now}, {'Key': 'mongo/notes', 'LastModified': now}]},
                {'Bucket': 'fictional', 'Prefix': 'mongo/', 'ContinuationToken': 'next'})
            self.assertEqual(['mongo/' + key], [r['Key'] for r in backup.objects(s3, 'fictional', 'mongo/')])
            stub.assert_no_pending_responses()

    def test_E11_T04_no_backup_or_escape_fails_before_writing(self):
        with patch.object(backup, 'objects', return_value=[]), tempfile.TemporaryDirectory() as directory:
            for key, message in [(None, 'No backup'), ('../other', 'not found')]:
                with self.assertRaisesRegex(backup.Failure, message):
                    backup.restore(None, 'bucket', 'mongo/', 'recipient', b'key', Path(directory), key)
            self.assertEqual([], list(Path(directory).iterdir()))

    def test_E11_T04_low_space_refused(self):
        with patch.object(backup.shutil, 'disk_usage') as disk:
            disk.return_value.free = backup.GIB - 1
            with self.assertRaisesRegex(backup.Failure, 'Insufficient'):
                backup.check_space('/work', backup.GIB)

    def test_E11_T04_restore_checks_disk_before_download(self):
        s3 = MagicMock()
        s3.head_object.return_value = {'Metadata': {'work-bytes': str(backup.GIB)}, 'ContentLength': 4096}
        with patch.object(backup, 'objects', return_value=[{'Key': 'mongo/fixture'}]), \
                patch.object(backup.shutil, 'disk_usage') as disk, tempfile.TemporaryDirectory() as directory:
            disk.return_value.free = backup.GIB - 1
            with self.assertRaisesRegex(backup.Failure, 'Insufficient'):
                backup.restore(s3, 'bucket', 'mongo/', 'recipient', b'key', Path(directory), 'mongo/fixture')
            s3.download_file.assert_not_called()
            self.assertEqual([], list(Path(directory).iterdir()))

    def test_E11_T04_dump_failure_closes_source_without_lock_or_upload(self):
        source, s3 = MagicMock(), MagicMock()
        with tempfile.TemporaryDirectory() as directory, \
                patch.dict(backup.os.environ, MONGO_BACKUP_USERNAME='backup', MONGO_BACKUP_PASSWORD='fictional'), \
                patch.object(backup, 'MongoClient', return_value=source), \
                patch.object(backup, 'estimate_space', return_value=backup.GIB), \
                patch.object(backup, 'check_space'), patch.object(backup, 'inventory', return_value=[]), \
                patch.object(backup, 'stream_dump', side_effect=backup.Failure('dump failed')):
            with self.assertRaisesRegex(backup.Failure, 'dump failed'):
                backup.backup(s3, 'bucket', 'mongo/', 'recipient', b'', Path(directory))
            source.close.assert_called_once()
            self.assertEqual([unittest.mock.call('ping')], source.admin.command.call_args_list)
            s3.upload_file.assert_not_called()

    def test_E11_T04_successful_backup_needs_only_list_and_put(self):
        source, s3 = MagicMock(), MagicMock()
        def stream(work, _):
            (work / 'dump.archive.gz.age').write_bytes(b'encrypted fixture')
            return Timestamp(100, 1), {'fictional.rows'}
        def encrypt(command, **kwargs):
            Path(command[-1]).write_bytes(b'encrypted manifest')
        with tempfile.TemporaryDirectory() as directory, \
                patch.dict(backup.os.environ, MONGO_BACKUP_USERNAME='backup', MONGO_BACKUP_PASSWORD='fictional'), \
                patch.object(backup, 'MongoClient', return_value=source), \
                patch.object(backup, 'estimate_space', return_value=backup.GIB), patch.object(backup, 'check_space'), \
                patch.object(backup, 'inventory', return_value=[('fictional', 'rows', {})]), \
                patch.object(backup, 'counts', return_value={'fictional.rows': 3}), \
                patch.object(backup, 'stream_dump', side_effect=stream), patch.object(backup, 'run', side_effect=encrypt):
            backup.backup(s3, 'bucket', 'mongo/', 'recipient', b'', Path(directory))
            self.assertEqual(['list_objects_v2', 'upload_file'], [c[0] for c in s3.mock_calls])
            self.assertEqual(['backup.tar'], [p.name for p in Path(directory).iterdir()])


if __name__ == '__main__':
    unittest.main(verbosity=2)
