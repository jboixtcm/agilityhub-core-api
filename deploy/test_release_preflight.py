"""Release input regressions; no Docker daemon, real credentials or network needed."""
import contextlib
import base64
import copy
import importlib.util
import io
import json
import re
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('release_preflight', Path(__file__).with_name('release-preflight.py'))
PREFLIGHT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREFLIGHT)
REGISTRY_DIGEST = 'sha256:' + 'a' * 64
IMAGE_ID = 'sha256:' + 'b' * 64
IMAGE = PREFLIGHT.REPOSITORY + ':sha-de50be0'


class ReleasePreflightTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='release-preflight-test-')
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.env_file = self.directory / 'core.env'
        self.env_file.write_text('DO_NOT_PRINT=private-test-value\n')
        self.env_file.chmod(0o600)
        mounts = []
        for target in PREFLIGHT.DIST_TARGETS:
            directory = self.directory / target.rsplit('/', 1)[1]
            (directory / 'assets').mkdir(parents=True)
            (directory / 'index.html').write_text('<!doctype html><script type="module" src="/assets/index-hash.js"></script>')
            (directory / 'assets/index-hash.js').write_text('document.body.textContent="application";')
            mounts.append({'type': 'bind', 'source': str(directory), 'target': target, 'read_only': True})
        self.config = {'services': {
            'core': {'image': IMAGE, 'environment': {'SPRING_PROFILES_ACTIVE': 'prod', 'TRUSTED_PROXY_PATTERN': r'172\.30\.240\.10'}},
            'mongo': {'environment': {'MONGO_INITDB_ROOT_PASSWORD': 'root-private', 'MONGODB_PASSWORD': 'app-private', 'MONGO_BACKUP_PASSWORD': 'backup-private'}},
            'caddy': {'networks': {'default': {'ipv4_address': '172.30.240.10'}}, 'volumes': mounts, 'ports': [{'published': '443'}]},
            'backup': {'environment': {'BACKUP_AGE_RECIPIENT': 'age1fictional', 'BACKUP_S3_BUCKET': 'private-backup', 'BACKUP_S3_ACCESS_KEY': 'writer-private', 'BACKUP_S3_SECRET_KEY': 'secret-private', 'BACKUP_S3_PREFIX': 'mongo/'}},
        }}
        self.metadata = [{'Id': IMAGE_ID, 'RepoDigests': [PREFLIGHT.REPOSITORY + '@' + REGISTRY_DIGEST], 'Os': 'linux', 'Architecture': 'amd64'}]
        for key in ('TWILIO_ACCOUNT_SID', 'TWILIO_AUTH_TOKEN', 'VAPID_PUBLIC_KEY', 'VAPID_PRIVATE_KEY', 'VAPID_SUBJECT',
                    'SENDGRID_API_KEY', 'MAIL_FROM_PLATFORM', 'SENDGRID_WEBHOOK_PUBLIC_KEY',
                    'EXPORT_S3_BUCKET', 'EXPORT_S3_REGION', 'EXPORT_S3_ACCESS_KEY', 'EXPORT_S3_SECRET_KEY',
                    'ATTACHMENT_S3_BUCKET', 'ATTACHMENT_S3_REGION', 'ATTACHMENT_S3_ACCESS_KEY', 'ATTACHMENT_S3_SECRET_KEY'):
            self.config['services']['core']['environment'][key] = 'fictional-provider-value'

    def filesystem_configuration(self):
        core, backup = self.config['services']['core'], self.config['services']['backup']
        core['environment'].update(HOSTED_DEMO_ENABLED='true', EMAIL_DELIVERY_ENABLED='false',
                FILE_STORAGE_MODE='filesystem', FILE_STORAGE_ROOT='/storage',
                FILE_STORAGE_SIGNING_KEY=base64.b64encode(b'f' * 32).decode())
        for key in list(core['environment']):
            if '_S3_' in key:
                core['environment'].pop(key)
        backup['environment'] = dict(HOSTED_DEMO_ENABLED='true', FILE_STORAGE_MODE='filesystem',
                BACKUP_STORAGE_MODE='filesystem', BACKUP_LOCAL_DIRECTORY='/backup', BACKUP_FILES_DIRECTORY='/storage',
                BACKUP_AGE_RECIPIENT='age1fictional')
        files, archives = self.directory.resolve() / 'files', self.directory.resolve() / 'backups'
        files.mkdir(mode=0o700); archives.mkdir(mode=0o700)
        marker = files / '.clubs-private-storage'
        marker.write_text('clubs-private-storage-v1\n'); marker.chmod(0o600)
        def bind(source, target, read_only=False):
            return dict(type='bind', source=str(source), target=target, read_only=read_only, bind={'create_host_path': False})
        core['volumes'] = [bind(files, '/storage')]
        backup['volumes'] = [bind(files, '/storage', True), bind(archives, '/backup')]
        return files, archives

    def test_filesystem_storage_requires_private_bound_volume_and_no_s3_credentials(self):
        files, _ = self.filesystem_configuration()
        PREFLIGHT.check_configuration(self.config)
        original = copy.deepcopy(self.config)
        for mutate in (
            lambda c: c['services']['core']['environment'].update(FILE_STORAGE_SIGNING_KEY='not base64'),
            lambda c: c['services']['core']['environment'].update(FILE_STORAGE_SIGNING_KEY=base64.b64encode(b'f' * 31).decode()),
            lambda c: c['services']['core']['environment'].update(FILE_STORAGE_ROOT='/tmp/files'),
            lambda c: c['services']['core']['environment'].update(HOSTED_DEMO_ENABLED='false'),
            lambda c: c['services']['core']['volumes'][0].update(type='volume'),
            lambda c: c['services']['core']['volumes'][0]['bind'].update(create_host_path=True),
            lambda c: c['services']['backup']['volumes'][0].update(read_only=False),
            lambda c: c['services']['backup']['volumes'][1].update(source=str(files)),
            lambda c: c['services']['backup']['environment'].update(BACKUP_STORAGE_MODE='s3'),
        ):
            config = copy.deepcopy(original); mutate(config)
            with self.subTest(mutate=mutate), self.assertRaises(PREFLIGHT.PreflightError):
                PREFLIGHT.check_configuration(config)
        files.chmod(0o755)
        with self.assertRaisesRegex(PREFLIGHT.PreflightError, '0700'):
            PREFLIGHT.check_configuration(self.config)
        files.chmod(0o700)
        (files / '.clubs-private-storage').chmod(0o644)
        with self.assertRaisesRegex(PREFLIGHT.PreflightError, 'volume marker'):
            PREFLIGHT.check_configuration(self.config)

    def test_preflight_automatically_resolves_filesystem_override_before_validation(self):
        self.filesystem_configuration()
        calls = []
        def docker(command):
            calls.append(command)
            return self.config if 'config' in command else self.metadata
        output = io.StringIO()
        with patch.object(PREFLIGHT, 'command_json', side_effect=docker), patch.dict('os.environ', {}, clear=True), contextlib.redirect_stdout(output):
            self.assertEqual(0, PREFLIGHT.main(['--env-file', str(self.env_file), '--expected-digest', REGISTRY_DIGEST]))
        self.assertEqual(3, len(calls))
        self.assertNotIn(str(PREFLIGHT.ROOT / 'deploy/compose.filesystem.yaml'), calls[0])
        self.assertIn(str(PREFLIGHT.ROOT / 'deploy/compose.filesystem.yaml'), calls[1])

    def test_hosted_demo_requires_exact_allowlist_and_keeps_real_email_requirements(self):
        environment = self.config['services']['core']['environment']
        environment['HOSTED_DEMO_ENABLED'] = 'true'
        for key in ('TWILIO_ACCOUNT_SID', 'TWILIO_AUTH_TOKEN', 'VAPID_PUBLIC_KEY', 'VAPID_PRIVATE_KEY', 'VAPID_SUBJECT'):
            environment[key] = ''
        for recipients in ('', '*', '*@example.test', 'tester@example.test,', 'tester@example.test;other@example.test'):
            environment['HOSTED_DEMO_EMAIL_ALLOWLIST'] = recipients
            with self.assertRaises(PREFLIGHT.PreflightError):
                PREFLIGHT.check_configuration(self.config)
        environment['HOSTED_DEMO_EMAIL_ALLOWLIST'] = 'Tester@example.test, other@example.test'
        PREFLIGHT.check_configuration(self.config)
        for key in ('SENDGRID_API_KEY', 'MAIL_FROM_PLATFORM', 'SENDGRID_WEBHOOK_PUBLIC_KEY'):
            value = environment.pop(key)
            with self.assertRaises(PREFLIGHT.PreflightError):
                PREFLIGHT.check_configuration(self.config)
            environment[key] = value
        environment['HOSTED_DEMO_ENABLED'] = 'false'
        with self.assertRaises(PREFLIGHT.PreflightError):
            PREFLIGHT.check_configuration(self.config)

    def test_disabled_internal_email_needs_no_provider_or_allowlist_but_requires_explicit_policy(self):
        env = self.config['services']['core']['environment']
        env.update(HOSTED_DEMO_ENABLED='true', EMAIL_DELIVERY_ENABLED='false', EMAIL_PROVIDER='brevo')
        for key in ('SENDGRID_API_KEY', 'SENDGRID_WEBHOOK_PUBLIC_KEY', 'MAIL_FROM_PLATFORM'):
            env.pop(key)
        PREFLIGHT.check_configuration(self.config)
        env['HOSTED_DEMO_ENABLED'] = 'false'
        with self.assertRaisesRegex(PREFLIGHT.PreflightError, 'EMAIL_DELIVERY_ENABLED=false'):
            PREFLIGHT.check_configuration(self.config)
        env['HOSTED_DEMO_ENABLED'] = 'true'
        env['EMAIL_DELIVERY_ENABLED'] = 'off'
        with self.assertRaisesRegex(PREFLIGHT.PreflightError, 'EMAIL_DELIVERY_ENABLED must'):
            PREFLIGHT.check_configuration(self.config)
        env['EMAIL_DELIVERY_ENABLED'] = 'true'
        with self.assertRaises(PREFLIGHT.PreflightError):
            PREFLIGHT.check_configuration(self.config)

    def test_invalid_hosted_demo_toggle_is_not_silently_accepted(self):
        self.config['services']['core']['environment']['HOSTED_DEMO_ENABLED'] = 'yes'
        with self.assertRaises(PREFLIGHT.PreflightError):
            PREFLIGHT.check_configuration(self.config)

    def test_recovery_only_requires_provider_credentials_without_general_email_allowlist(self):
        env = self.config['services']['core']['environment']
        env.update(HOSTED_DEMO_ENABLED='true', EMAIL_DELIVERY_ENABLED='false', EMAIL_RECOVERY_ENABLED='true',
                   EMAIL_PROVIDER='brevo', BREVO_API_KEY='fictional-api-key', BREVO_WEBHOOK_TOKEN='r' * 32)
        PREFLIGHT.check_configuration(self.config)
        for key in ('BREVO_API_KEY', 'BREVO_WEBHOOK_TOKEN', 'MAIL_FROM_PLATFORM'):
            value = env.pop(key)
            with self.subTest(key=key), self.assertRaises(PREFLIGHT.PreflightError):
                PREFLIGHT.check_configuration(self.config)
            env[key] = value
        env['EMAIL_RECOVERY_ENABLED'] = 'yes'
        with self.assertRaisesRegex(PREFLIGHT.PreflightError, 'EMAIL_RECOVERY_ENABLED must'):
            PREFLIGHT.check_configuration(self.config)

    def test_brevo_uses_its_own_credentials_and_rejects_weak_webhook_auth(self):
        env = self.config['services']['core']['environment']
        env.update(EMAIL_PROVIDER='brevo', BREVO_API_KEY='fictional-api-key', BREVO_WEBHOOK_TOKEN='f' * 32)
        env.pop('SENDGRID_API_KEY')
        env.pop('SENDGRID_WEBHOOK_PUBLIC_KEY')
        PREFLIGHT.check_configuration(self.config)
        for key in ('BREVO_API_KEY', 'BREVO_WEBHOOK_TOKEN', 'MAIL_FROM_PLATFORM'):
            value = env.pop(key)
            with self.subTest(key=key), self.assertRaises(PREFLIGHT.PreflightError):
                PREFLIGHT.check_configuration(self.config)
            env[key] = value
        for token in ('short', 'f' * 257, ' ' * 32, 'f' * 31 + '\n'):
            env['BREVO_WEBHOOK_TOKEN'] = token
            with self.assertRaises(PREFLIGHT.PreflightError):
                PREFLIGHT.check_configuration(self.config)
        env['EMAIL_PROVIDER'] = 'unknown'
        with self.assertRaises(PREFLIGHT.PreflightError):
            PREFLIGHT.check_configuration(self.config)

    def test_production_env_example_proxy_escaping_matches_caddy_ip_exactly(self):
        example = (PREFLIGHT.ROOT / 'deploy/.env.prod.example').read_text()
        # Compose single-quoted values preserve backslashes literally.
        pattern = re.search(r"^TRUSTED_PROXY_PATTERN='([^']+)'$", example, re.M).group(1)
        self.assertIsNotNone(re.fullmatch(pattern, '172.30.240.10'))
        self.assertIsNone(re.fullmatch(pattern, '172x30x240x10'))
        self.assertIsNone(re.fullmatch(pattern, '172.30.240.11'))

    def test_reviewed_sha_and_registry_digest_produce_a_secret_free_artifact_record(self):
        calls = []

        def docker(command):
            calls.append(command)
            return self.config if 'config' in command else self.metadata

        output, errors = io.StringIO(), io.StringIO()
        with patch.object(PREFLIGHT, 'command_json', side_effect=docker), patch.dict('os.environ', {}, clear=True), contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            self.assertEqual(0, PREFLIGHT.main(['--env-file', str(self.env_file), '--expected-digest', REGISTRY_DIGEST]))
        result = json.loads(output.getvalue())
        self.assertEqual('PASS', result['result'])
        self.assertEqual(IMAGE_ID, result['image']['imageId'])
        self.assertEqual(3, len(result['webArtifacts']))
        self.assertEqual('', errors.getvalue())
        self.assertNotIn('private', output.getvalue())
        self.assertEqual(['config', '--format', 'json'], calls[0][-3:])
        self.assertEqual(['docker', 'image', 'inspect', IMAGE], calls[1])

    def test_main_latest_local_and_ci_tags_are_refused(self):
        for tag in ('main', 'latest', 'e11-t12', 'ci-a123-amd64', 'sha-REPLACE', 'sha-123456', 'sha-ABCDEFG'):
            with self.subTest(tag=tag):
                self.config['services']['core']['image'] = PREFLIGHT.REPOSITORY + ':' + tag
                with self.assertRaises(PREFLIGHT.PreflightError):
                    PREFLIGHT.check_configuration(self.config)

    def test_digest_pinned_sha_tag_is_accepted_but_wrong_digest_fails(self):
        image = IMAGE + '@' + REGISTRY_DIGEST
        self.config['services']['core']['image'] = image
        self.assertEqual((image, REGISTRY_DIGEST), PREFLIGHT.check_configuration(self.config))
        PREFLIGHT.check_image(image, REGISTRY_DIGEST, REGISTRY_DIGEST, self.metadata)
        with self.assertRaises(PREFLIGHT.PreflightError):
            PREFLIGHT.check_image(image, REGISTRY_DIGEST, 'sha256:' + 'c' * 64, self.metadata)

    def test_unpinned_tag_needs_a_reviewed_expected_digest(self):
        with self.assertRaises(PREFLIGHT.PreflightError):
            PREFLIGHT.check_image(IMAGE, None, None, self.metadata)

    def test_local_tag_without_registry_digest_is_not_release_evidence(self):
        self.metadata[0]['RepoDigests'] = []
        with self.assertRaises(PREFLIGHT.PreflightError):
            PREFLIGHT.check_image(IMAGE, None, REGISTRY_DIGEST, self.metadata)

    def test_expected_registry_digest_cannot_be_an_image_id(self):
        with self.assertRaises(PREFLIGHT.PreflightError):
            PREFLIGHT.check_image(IMAGE, None, IMAGE_ID, self.metadata)

    def test_wrong_local_image_digest_is_refused(self):
        with self.assertRaises(PREFLIGHT.PreflightError):
            PREFLIGHT.check_image(IMAGE, None, 'sha256:' + 'c' * 64, self.metadata)

    def test_environment_permissions_follow_documented_private_file_requirement(self):
        self.assertEqual(self.env_file.resolve(), PREFLIGHT.check_env_file(self.env_file))
        self.env_file.chmod(0o400)
        PREFLIGHT.check_env_file(self.env_file)
        self.env_file.chmod(0o644)
        with self.assertRaises(PREFLIGHT.PreflightError):
            PREFLIGHT.check_env_file(self.env_file)

    def test_environment_inside_checkout_is_refused(self):
        with patch.object(PREFLIGHT, 'ROOT', self.directory), self.assertRaises(PREFLIGHT.PreflightError):
            PREFLIGHT.check_env_file(self.env_file)

    def test_placeholder_credentials_and_absent_backup_are_refused(self):
        original = copy.deepcopy(self.config)
        self.config['services']['core']['environment']['SENDGRID_API_KEY'] = 'REPLACE_BEFORE_RELEASE'
        with self.assertRaisesRegex(PREFLIGHT.PreflightError, 'SENDGRID_API_KEY'):
            PREFLIGHT.check_configuration(self.config)
        self.config = original
        self.config['services']['backup']['environment']['BACKUP_S3_BUCKET'] = ''
        with self.assertRaisesRegex(PREFLIGHT.PreflightError, 'BACKUP_S3_BUCKET'):
            PREFLIGHT.check_configuration(self.config)

    def test_private_backup_identity_is_forbidden_in_ordinary_compose(self):
        self.config['services']['backup']['environment']['BACKUP_AGE_IDENTITY'] = 'private'
        with self.assertRaises(PREFLIGHT.PreflightError):
            PREFLIGHT.check_configuration(self.config)

    def test_local_profile_published_mongo_and_broad_proxy_are_refused(self):
        for mutate in (
            lambda config: config['services']['core']['environment'].update(SPRING_PROFILES_ACTIVE='prod,local'),
            lambda config: config['services']['mongo'].update(ports=[{'published': '27017'}]),
            lambda config: config['services']['core']['environment'].update(TRUSTED_PROXY_PATTERN='.*'),
            lambda config: config['services'].update(seed={}),
        ):
            with self.subTest(mutate=mutate), self.assertRaises(PREFLIGHT.PreflightError):
                config = copy.deepcopy(self.config)
                mutate(config)
                PREFLIGHT.check_configuration(config)

    def test_shared_mongo_credentials_are_refused(self):
        self.config['services']['mongo']['environment']['MONGO_BACKUP_PASSWORD'] = 'app-private'
        with self.assertRaises(PREFLIGHT.PreflightError):
            PREFLIGHT.check_configuration(self.config)

    def test_spa_hash_changes_if_any_release_asset_changes(self):
        before = PREFLIGHT.check_spas(self.config)
        (self.directory / 'clubs/assets/index-hash.js').write_text('changed')
        after = PREFLIGHT.check_spas(self.config)
        self.assertNotEqual(before['/srv/clubs'], after['/srv/clubs'])
        self.assertEqual(before['/srv/id'], after['/srv/id'])

    def test_proof_html_missing_module_and_uncompiled_source_are_refused(self):
        index = self.directory / 'id/index.html'
        for html in ('Fictional local deployment fixture', '<h1>Placeholder</h1>',
                     '<script type="module" src="/src/main.tsx"></script>',
                     '<script type="module" src="/missing.js"></script>',
                     '<script type="module" src="https://example.test/app.js"></script>'):
            with self.subTest(html=html):
                index.write_text(html)
                with self.assertRaises(PREFLIGHT.PreflightError):
                    PREFLIGHT.check_spas(self.config)

    def test_path_escape_and_artifact_symlinks_are_refused(self):
        outside = self.directory / 'outside.js'
        outside.write_text('outside')
        (self.directory / 'id/index.html').write_text('<script type="module" src="../outside.js"></script>')
        with self.assertRaises(PREFLIGHT.PreflightError):
            PREFLIGHT.check_spas(self.config)
        (self.directory / 'id/index.html').write_text('<script type="module" src="/assets/index-hash.js"></script>')
        (self.directory / 'id/assets/alias.js').symlink_to(outside)
        with self.assertRaises(PREFLIGHT.PreflightError):
            PREFLIGHT.check_spas(self.config)

    def test_shared_or_writable_spa_mount_is_refused(self):
        mounts = self.config['services']['caddy']['volumes']
        mounts[0]['read_only'] = False
        with self.assertRaises(PREFLIGHT.PreflightError):
            PREFLIGHT.check_spas(self.config)
        mounts[0]['read_only'] = True
        mounts[1]['source'] = mounts[0]['source']
        with self.assertRaises(PREFLIGHT.PreflightError):
            PREFLIGHT.check_spas(self.config)

    def test_compose_error_output_cannot_leak_secrets(self):
        failure = subprocess.CompletedProcess([], 1, stdout='private-secret', stderr='secret-password')
        with patch.object(PREFLIGHT.subprocess, 'run', return_value=failure), self.assertRaises(PREFLIGHT.PreflightError) as caught:
            PREFLIGHT.command_json(['docker', 'compose'])
        self.assertNotIn('secret', str(caught.exception))
        self.assertNotIn('password', str(caught.exception))

    def test_no_local_override_or_docker_mutation_can_be_requested(self):
        with patch.dict('os.environ', {'DEPLOY_LOCAL': '1'}), patch.object(PREFLIGHT, 'command_json') as command, contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(2, PREFLIGHT.main(['--env-file', str(self.env_file)]))
            command.assert_not_called()


if __name__ == '__main__':
    unittest.main()
