"""Internal filesystem backup gates, immutable ciphertext and safe file recovery."""
import hashlib
import io
import json
import os
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

import backup


class FilesystemBackupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.files = self.root / 'files'
        self.files.mkdir(mode=0o700)
        marker = self.files / '.clubs-private-storage'
        marker.write_text('clubs-private-storage-v1\n')
        marker.chmod(0o600)
        self.archives = self.root / 'archives'
        self.archives.mkdir(mode=0o700)
        self.key = 'mongo/20000101T000000Z-' + 'a' * 32 + '.tar'
        self.store = backup.FilesystemStore(self.archives, self.files)
        self.env = {'BACKUP_STORAGE_MODE': 'filesystem', 'HOSTED_DEMO_ENABLED': 'true',
                    'FILE_STORAGE_MODE': 'filesystem', 'BACKUP_LOCAL_DIRECTORY': str(self.archives),
                    'BACKUP_FILES_DIRECTORY': str(self.files), 'BACKUP_FILES_QUIESCED': 'true',
                    'BACKUP_AGE_RECIPIENT': 'age1-fixture', 'MONGO_BACKUP_USERNAME': 'backup',
                    'MONGO_BACKUP_PASSWORD': 'fixture', 'BACKUP_S3_PREFIX': 'mongo/'}

    def test_configuration_needs_internal_mode_quiescence_and_private_marked_roots(self):
        with patch.dict(os.environ, self.env, clear=True), patch.object(backup, 'run'), \
                patch.object(backup.boto3, 'client') as provider:
            store, bucket, prefix, _, _ = backup.configuration('backup')
            self.assertIsInstance(store, backup.FilesystemStore)
            self.assertEqual(('filesystem', 'mongo/'), (bucket, prefix))
            provider.assert_not_called()
            with patch.dict(os.environ, {'BACKUP_S3_PREFIX': ''}):
                self.assertEqual('mongo/', backup.configuration('backup')[2])
            for key, invalid in [('HOSTED_DEMO_ENABLED', 'false'), ('FILE_STORAGE_MODE', 's3'),
                                 ('BACKUP_FILES_QUIESCED', ''),
                                 ('BACKUP_LOCAL_DIRECTORY', str(self.files))]:
                with self.subTest(key=key), patch.dict(os.environ, {key: invalid}), self.assertRaises(backup.Failure):
                    backup.configuration('backup')
            self.files.chmod(0o755)
            with self.assertRaisesRegex(backup.Failure, 'group'):
                backup.configuration('backup')
            self.files.chmod(0o700)
            (self.files / '.clubs-private-storage').unlink()
            with self.assertRaisesRegex(backup.Failure, 'marker'):
                backup.configuration('backup')

    def test_archive_storage_is_immutable_and_refuses_escape_and_symlinks(self):
        source = self.root / 'ciphertext'
        source.write_bytes(b'ciphertext')
        with patch.object(backup, 'check_space'):
            self.store.upload_file(source, '', self.key, ExtraArgs={}, Config=None)
            self.assertEqual(0o600, (self.archives / self.key).stat().st_mode & 0o777)
            with self.assertRaises(FileExistsError):
                self.store.upload_file(source, '', self.key, ExtraArgs={}, Config=None)
            for key in ('../' + self.key, '/' + self.key, 'mongo/nested/a.tar', 'mongo/../a.tar', 'other/' + self.key.split('/', 1)[1]):
                with self.subTest(key=key), self.assertRaises(backup.Failure):
                    self.store.upload_file(source, '', key, ExtraArgs={}, Config=None)
        self.assertEqual([self.key], [x['Key'] for x in backup.objects(self.store, '', 'mongo/')])
        downloaded = self.root / 'downloaded'
        self.store.download_file('', self.key, downloaded, Config=None)
        self.assertEqual(source.read_bytes(), downloaded.read_bytes())
        (self.archives / self.key).unlink()
        (self.archives / self.key).symlink_to(source)
        with self.assertRaises(backup.Failure):
            self.store.head_object(Bucket='', Key=self.key)

    def test_app_inventory_refuses_symlinks_hardlinks_and_special_files(self):
        regular = self.files / 'data'
        regular.write_bytes(b'private test data')
        link = self.files / 'linked'
        link.symlink_to(regular)
        with self.assertRaises(backup.Failure):
            backup.file_inventory(self.files)
        link.unlink()
        os.link(regular, link)
        with self.assertRaises(backup.Failure):
            backup.file_inventory(self.files)
        link.unlink()
        os.mkfifo(link)
        with self.assertRaises(backup.Failure):
            backup.file_inventory(self.files)

    def test_real_age_file_roundtrip_keeps_directory_and_file_inventory(self):
        identity = backup.run(['age-keygen'])
        recipient = backup.run(['age-keygen', '-y'], input=identity).decode().strip()
        child = self.files / 'attachments'
        child.mkdir(mode=0o700)
        (child / 'file').write_bytes(b'private test bytes' * 1024)
        (self.files / 'exports').mkdir(mode=0o700)
        expected = backup.file_inventory(self.files)
        cipher = self.root / 'files.tar.age'
        backup.encrypt_files(self.files, expected, recipient, cipher)
        self.assertNotIn(b'private test bytes', cipher.read_bytes())
        keyfile = self.root / 'identity'
        keyfile.write_bytes(identity)
        archive = self.root / 'files.tar'
        backup.run(['age', '-d', '-i', str(keyfile), '-o', str(archive), str(cipher)])
        output = self.root / 'restored'
        backup.verify_files(archive, expected, output)
        self.assertEqual(expected, backup.file_inventory(output))
        broken = bytearray(cipher.read_bytes())
        broken[-1] ^= 1
        cipher.write_bytes(broken)
        with self.assertRaises(backup.Failure):
            backup.run(['age', '-d', '-i', str(keyfile), str(cipher)])

    def test_restore_refuses_paths_links_duplicates_missing_and_tampered_files(self):
        expected = {'file': {'type': 'file', 'size': 4, 'sha256': hashlib.sha256(b'data').hexdigest()}}
        cases = [('traversal', [('..', tarfile.DIRTYPE, b'')]),
                 ('absolute', [('/file', tarfile.REGTYPE, b'data')]),
                 ('symlink', [('file', tarfile.SYMTYPE, b'')]),
                 ('hardlink', [('file', tarfile.LNKTYPE, b'')]),
                 ('duplicate', [('file', tarfile.REGTYPE, b'data')] * 2),
                 ('missing', []), ('tamper', [('file', tarfile.REGTYPE, b'evil')])]
        for name, rows in cases:
            archive = self.root / (name + '.tar')
            with tarfile.open(archive, 'w') as bundle:
                for path, kind, content in rows:
                    info = tarfile.TarInfo(path)
                    info.type = kind
                    info.size = len(content)
                    info.linkname = '/etc/passwd' if kind in (tarfile.SYMTYPE, tarfile.LNKTYPE) else ''
                    bundle.addfile(info, io.BytesIO(content))
            with self.subTest(name=name), self.assertRaises(backup.Failure):
                backup.verify_files(archive, expected, self.root / name)

    def test_restore_filesystem_rejects_s3_archive_without_application_files(self):
        source = self.root / 'old.tar'
        with tarfile.open(source, 'w') as bundle:
            for name in ('dump.archive.gz.age', 'manifest.json.age'):
                bundle.addfile(tarfile.TarInfo(name))
        with patch.object(backup, 'check_space'):
            self.store.upload_file(source, '', self.key, ExtraArgs={}, Config=None)
            work = self.root / 'work'
            work.mkdir()
            with self.assertRaisesRegex(backup.Failure, 'archive contents'):
                backup.restore(self.store, '', 'mongo/', '', b'', work, self.key)


if __name__ == '__main__':
    unittest.main(verbosity=2)
