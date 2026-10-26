"""Release input regressions; no Docker daemon, real credentials or network needed."""
import contextlib
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
                    'SENDGRID_API_KEY', 'MAIL_FROM_PLATFORM', 'SENDGRID_WEBHOOK_PUBLIC_KEY'):
            self.config['services']['core']['environment'][key] = 'fictional-provider-value'

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

    def test_invalid_hosted_demo_toggle_is_not_silently_accepted(self):
        self.config['services']['core']['environment']['HOSTED_DEMO_ENABLED'] = 'yes'
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
