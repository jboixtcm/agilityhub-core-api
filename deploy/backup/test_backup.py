"""Operational failure-path tests; S3 stubs are checked against botocore's real model."""
import datetime as dt
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch

import boto3
from botocore.stub import Stubber

import backup


class BackupSafetyTest(unittest.TestCase):
    def test_E11_T04_counts_cover_user_collections_without_internal_cluster_keys(self):
        client = MagicMock()
        client.list_database_names.return_value = ['local', 'admin', 'config', 'fictional']
        admin, application = MagicMock(), MagicMock()
        client.__getitem__.side_effect = {'admin': admin, 'fictional': application}.__getitem__
        admin.list_collections.return_value = [
            {'name': 'system.keys', 'type': 'collection'}, {'name': 'system.users', 'type': 'collection'}]
        application.list_collections.return_value = [
            {'name': 'rows', 'type': 'collection'}, {'name': 'empty', 'type': 'collection'},
            {'name': 'view', 'type': 'view'}, {'name': 'system.profile', 'type': 'collection'}]
        admin.__getitem__.return_value.count_documents.return_value = 3
        row, empty = MagicMock(), MagicMock()
        row.count_documents.return_value = 7
        empty.count_documents.return_value = 0
        application.__getitem__.side_effect = {'rows': row, 'empty': empty}.__getitem__
        self.assertEqual({'admin.system.users': 3, 'fictional.empty': 0, 'fictional.rows': 7}, backup.counts(client))
        admin.__getitem__.assert_called_once_with('system.users')

    def test_E11_T04_retention_paginates_and_only_deletes_expired_backup_objects(self):
        s3 = boto3.client('s3', region_name='us-east-1', aws_access_key_id='fictional', aws_secret_access_key='fictional')
        now = dt.datetime.now(dt.timezone.utc)
        old = now - dt.timedelta(days=31)
        expired = 'mongo/20000101T000000Z-' + 'a' * 32 + '.tar.age'
        current = 'mongo/20000102T000000Z-' + 'b' * 32 + '.tar.age'
        recent = 'mongo/20000103T000000Z-' + 'c' * 32 + '.tar.age'
        with Stubber(s3) as stub:
            stub.add_response('list_objects_v2', {'IsTruncated': True, 'NextContinuationToken': 'next', 'Contents': [
                {'Key': expired, 'LastModified': old}, {'Key': 'mongo/notes.txt', 'LastModified': old},
                {'Key': 'mongo/nested/' + expired.split('/')[1], 'LastModified': old}]},
                {'Bucket': 'fictional-backups', 'Prefix': 'mongo/'})
            stub.add_response('list_objects_v2', {'IsTruncated': False, 'Contents': [
                {'Key': current, 'LastModified': old}, {'Key': recent, 'LastModified': now}]},
                {'Bucket': 'fictional-backups', 'Prefix': 'mongo/', 'ContinuationToken': 'next'})
            stub.add_response('delete_object', {}, {'Bucket': 'fictional-backups', 'Key': expired})
            self.assertEqual(1, backup.prune(s3, 'fictional-backups', 'mongo/', 30, current))
            stub.assert_no_pending_responses()

    def test_E11_T04_no_archive_is_an_error(self):
        with patch.object(backup, 'objects', return_value=[]), tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(backup.Failure, 'No backup'):
                backup.restore(None, 'bucket', 'mongo/', 30, b'key', 'recipient', Path(directory), None)
            self.assertEqual([], list(Path(directory).iterdir()))

    def test_E11_T04_named_archive_cannot_escape_prefix(self):
        with patch.object(backup, 'objects', return_value=[]), tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(backup.Failure, 'not found'):
                backup.restore(None, 'bucket', 'mongo/', 30, b'key', 'recipient', Path(directory), '../other')
            self.assertEqual([], list(Path(directory).iterdir()))

    def test_E11_T04_dump_failure_unlocks_and_never_uploads_or_prunes(self):
        source = MagicMock()
        storage = MagicMock()
        with tempfile.TemporaryDirectory() as directory, \
                patch.dict(backup.os.environ, MONGO_BACKUP_USERNAME='backup', MONGO_BACKUP_PASSWORD='fictional'), \
                patch.object(backup, 'MongoClient', return_value=source), \
                patch.object(backup, 'counts', return_value={'fictional.rows': 2}), \
                patch.object(backup, 'run', side_effect=backup.Failure('dump failed')), \
                patch.object(backup, 'prune') as prune:
            with self.assertRaisesRegex(backup.Failure, 'dump failed'):
                backup.backup(storage, 'bucket', 'mongo/', 30, b'key', 'recipient', Path(directory))
            source.admin.command.assert_any_call({'fsyncUnlock': 1})
            storage.upload_file.assert_not_called()
            prune.assert_not_called()

    def test_E11_T04_upload_failure_does_not_prune_and_source_is_already_unlocked(self):
        source = MagicMock()
        storage = MagicMock()
        storage.upload_file.side_effect = RuntimeError('unavailable')
        with tempfile.TemporaryDirectory() as directory, \
                patch.dict(backup.os.environ, MONGO_BACKUP_USERNAME='backup', MONGO_BACKUP_PASSWORD='fictional'), \
                patch.object(backup, 'MongoClient', return_value=source), \
                patch.object(backup, 'counts', return_value={'fictional.rows': 2}), \
                patch.object(backup, 'run', return_value=b''), patch.object(backup, 'prune') as prune:
            (Path(directory) / 'dump.archive.gz').write_bytes(b'fictional')
            with self.assertRaisesRegex(RuntimeError, 'unavailable'):
                backup.backup(storage, 'bucket', 'mongo/', 30, b'key', 'recipient', Path(directory))
            source.admin.command.assert_any_call({'fsyncUnlock': 1})
            prune.assert_not_called()


if __name__ == '__main__':
    unittest.main(verbosity=2)
