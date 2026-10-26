"""The host backup entry point proves app writers stopped before local file capture."""
import importlib.util
import json
import os
from pathlib import Path
import types
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('backup_runner', Path(__file__).with_name('backup-runner.py'))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class BackupRunnerFilesystemTest(unittest.TestCase):
    def config(self, **updates):
        env = dict(BACKUP_STORAGE_MODE='filesystem', HOSTED_DEMO_ENABLED='true', FILE_STORAGE_MODE='filesystem',
                   BACKUP_LOCAL_DIRECTORY='/backup', BACKUP_FILES_DIRECTORY='/storage',
                   BACKUP_AGE_RECIPIENT='age1-fixture')
        env.update(updates)
        return types.SimpleNamespace(returncode=0, stdout=json.dumps({'services': {'backup': {'environment': env}}}))

    def test_stopped_app_selects_filesystem_override_and_proves_quiescence(self):
        stopped = types.SimpleNamespace(returncode=0, stdout='')
        with patch.dict(os.environ, {}, clear=True), patch.object(runner.sys, 'argv', ['runner', 'backup']), \
                patch.object(runner.subprocess, 'run', side_effect=[self.config(), self.config(), stopped]) as run, \
                patch.object(runner.subprocess, 'call', return_value=0) as call:
            self.assertEqual(0, runner.main())
            self.assertIn(str(Path(runner.__file__).parent / 'compose.filesystem.yaml'), run.call_args_list[1].args[0])
            self.assertEqual(['ps', '--status', 'running', '--quiet', 'core'], run.call_args_list[2].args[0][-5:])
            self.assertIn('BACKUP_FILES_QUIESCED=true', call.call_args.args[0])

    def test_running_app_or_failed_inspection_creates_no_backup_container(self):
        for stopped in (types.SimpleNamespace(returncode=0, stdout='container-id'), types.SimpleNamespace(returncode=1, stdout='')):
            with patch.dict(os.environ, {}, clear=True), patch.object(runner.sys, 'argv', ['runner', 'backup']), \
                    patch.object(runner.subprocess, 'run', side_effect=[self.config(), self.config(), stopped]), \
                    patch.object(runner.subprocess, 'call') as call, self.assertRaisesRegex(ValueError, 'Stop the core'):
                runner.main()
            call.assert_not_called()

    def test_non_internal_policy_and_local_proof_override_are_refused(self):
        for env, config in (({}, self.config(HOSTED_DEMO_ENABLED='false')), ({'DEPLOY_LOCAL': '1'}, self.config())):
            with patch.dict(os.environ, env, clear=True), patch.object(runner.sys, 'argv', ['runner', 'backup']), \
                    patch.object(runner.subprocess, 'run', side_effect=[config, config]), \
                    patch.object(runner.subprocess, 'call') as call, self.assertRaises(ValueError):
                runner.main()
            call.assert_not_called()

    def test_restore_passes_private_identity_only_by_environment(self):
        with patch.dict(os.environ, {'BACKUP_AGE_IDENTITY': 'fixture-private-identity'}, clear=True), \
                patch.object(runner.sys, 'argv', ['runner', 'restore', '--verify', 'mongo/selected.tar']), \
                patch.object(runner.subprocess, 'run', side_effect=[self.config(), self.config()]), \
                patch.object(runner.subprocess, 'call', return_value=0) as call:
            self.assertEqual(0, runner.main())
            self.assertIn('BACKUP_AGE_IDENTITY', call.call_args.args[0])
            self.assertNotIn('fixture-private-identity', str(call.call_args))
            self.assertNotIn('BACKUP_FILES_QUIESCED=true', call.call_args.args[0])


if __name__ == '__main__':
    unittest.main()
