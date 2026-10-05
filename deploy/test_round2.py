"""Round-two deployment contracts; runtime behavior is exercised by local-proof.py."""
import json
from pathlib import Path
import re
import subprocess
import unittest

ROOT = Path(__file__).resolve().parent.parent


class DeploymentReviewTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.prod = json.loads(subprocess.check_output([
            'docker', 'compose', '--env-file', 'deploy/.env.prod.example', '--profile', 'ops',
            '-f', 'deploy/compose.prod.yaml', 'config', '--format', 'json'], cwd=ROOT))
        cls.doc = (ROOT / 'docs/DEPLOY.md').read_text()

    def test_E11_T04_01_no_write_lock(self):
        code = (ROOT / 'deploy/backup/backup.py').read_text()
        self.assertNotIn("'fsync'", code)
        self.assertIn("'atClusterTime'", code)

    def test_E11_T04_02_production_local_core(self):
        env = dict(__import__('os').environ, LOCAL_MONGODB_URI='mongodb://fictional:fictional@mongo/agilityhub', SEED_PASSWORD='fictional')
        local = json.loads(subprocess.check_output([
            'docker', 'compose', '--env-file', 'deploy/.env.prod.example',
            '-f', 'deploy/compose.prod.yaml', '-f', 'deploy/compose.prod.local.yaml',
            'config', '--format', 'json'], cwd=ROOT, env=env))
        core = local['services']['core']['environment']
        self.assertEqual('prod', core['SPRING_PROFILES_ACTIVE'])
        self.assertEqual('local', local['services']['seed']['environment']['SPRING_PROFILES_ACTIVE'])
        for prefix in ('EXPORT', 'ATTACHMENT'):
            self.assertEqual('http://minio:9000', core[prefix + '_S3_ENDPOINT'])
        for key in ('SENDGRID_API_KEY', 'TWILIO_AUTH_TOKEN', 'VAPID_PRIVATE_KEY'):
            self.assertTrue(core[key])

    def test_E11_T04_03_bounded_disk_work(self):
        ops = self.prod['services']['backup']
        self.assertGreater(int(ops.get('mem_limit', 0)), 0)
        self.assertTrue(any(v['target'] == '/work' and v['type'] == 'volume' for v in ops.get('volumes', [])))

    def test_E11_T04_04_backup_only_role(self):
        self.assertNotIn('hostManager', (ROOT / 'deploy/mongo-health.js').read_text())

    def test_E11_T04_05_spa_cache_after_rewrite(self):
        caddy = (ROOT / 'deploy/Caddyfile').read_text()
        self.assertRegex(caddy, r'route\s*\{\s*try_files[^\n]+\n\s*header /index.html Cache-Control "no-cache"')

    def test_E11_T04_06_cron_alert_and_lock_exit(self):
        cron = re.search(r'```cron\n(.*?)\n```', self.doc, re.S).group(1)
        self.assertIn('flock -n -E 75', cron)
        self.assertIn('logger -p user.err', cron)

    def test_E11_T04_06_cron_executes_alert_for_failure_and_busy_lock(self):
        cron = re.search(r'```cron\n(.*?)\n```', self.doc, re.S).group(1).split(' ', 5)[5]
        cron = cron.replace('/opt/agilityhub/core', '/tmp/cron-proof').replace('/etc/agilityhub/core.env', '/tmp/env')
        cron = cron.replace('/var/lock/agilityhub-backup.lock', '/tmp/cron-proof/lock')
        cron = cron.replace('/var/log/agilityhub-backup.log', '/tmp/cron-proof/backup.log')
        cron = cron.replace('/usr/bin/logger', '/tmp/cron-proof/logger')
        script = '''set -eu
mkdir -p /tmp/cron-proof/bin
printf '#!/bin/sh\\nexit 9\\n' > /tmp/cron-proof/bin/backup-mongo
printf '#!/bin/sh\\nprintf "%%s\\n" "$*" >> /tmp/cron-proof/alerts\\n' > /tmp/cron-proof/logger
chmod +x /tmp/cron-proof/bin/backup-mongo /tmp/cron-proof/logger
'''
        script += "set +e\nbash -c " + __import__('shlex').quote(cron) + "\nrc=$?\nset -e\ntest \"$rc\" = 9\n"
        script += "exec 9>/tmp/cron-proof/lock\nflock 9\nset +e\nbash -c " + __import__('shlex').quote(cron) + " 9>&-\nrc=$?\nset -e\ntest \"$rc\" = 75\n"
        script += "grep -q 'exit=9' /tmp/cron-proof/alerts\ngrep -q 'exit=75' /tmp/cron-proof/alerts\n"
        subprocess.run(['docker', 'run', '--rm', '--network', 'none', '-i', '--entrypoint', 'bash',
                        'agilityhub-backup:2'], input=script, text=True, check=True, capture_output=True)

    def test_E11_T04_07_allow_path_uses_same_bridge(self):
        caddy = (ROOT / 'deploy/Caddyfile').read_text()
        self.assertIn('reverse_proxy {$CADDY_ASK_UPSTREAM}', caddy)
        self.assertEqual('core:8080', self.prod['services']['caddy']['environment']['CADDY_ASK_UPSTREAM'])
        self.assertNotIn('ask-stub', self.prod['services'])

    def test_E11_T04_08_public_recipient_and_lifecycle(self):
        ops = self.prod['services']['backup']['environment']
        self.assertIn('BACKUP_AGE_RECIPIENT', ops)
        self.assertNotIn('BACKUP_ENCRYPTION_KEY', ops)
        lifecycle = json.loads((ROOT / 'deploy/backup/lifecycle.json').read_text())
        self.assertEqual(30, lifecycle['Rules'][0]['Expiration']['Days'])
        policy = json.loads((ROOT / 'deploy/backup/writer-policy.json').read_text())
        actions = {a for s in policy['Statement'] for a in s['Action']}
        self.assertEqual({'s3:ListBucket', 's3:PutObject'}, actions)

    def test_E11_T04_09_retired_key_absent(self):
        for file in ('deploy/.env.prod.example', 'deploy/compose.prod.yaml'):
            self.assertNotIn('MIGRATION_BANK_KEY', (ROOT / file).read_text())

    def test_E11_T04_10_portable_docs_and_pinned_images(self):
        self.assertNotIn('/Users/', self.doc)
        self.assertRegex(self.prod['services']['mongo']['image'], r'mongo:(?:\d+\.){2}\d+@sha256:')
        self.assertEqual('never', self.prod['services']['mongo']['pull_policy'])
        self.assertNotIn('e11-t04', self.prod['services']['backup']['image'])

    def test_E11_T04_11_billing_provider_key(self):
        self.assertTrue(self.prod['services']['core']['environment'].get('BILLING_SECRETS_KEY'))
        self.assertIn("'BILLING_SECRETS_KEY'", (ROOT / 'deploy/local-proof.py').read_text())


if __name__ == '__main__':
    unittest.main(verbosity=2)
