#!/usr/bin/env python3
"""Read-only release checks. Compose's resolved secrets never leave this process."""
import argparse
import hashlib
from html.parser import HTMLParser
import ipaddress
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parent.parent
REPOSITORY = 'ghcr.io/jboixtcm/agilityhub-core-api'
DIGEST = re.compile(r'sha256:[0-9a-f]{64}')
IMAGE = re.compile(re.escape(REPOSITORY) + r':sha-([0-9a-f]{7,40})(?:@(sha256:[0-9a-f]{64}))?')
DIST_TARGETS = ('/srv/id', '/srv/clubs', '/srv/clubsadmin')


class PreflightError(Exception):
    """Only fixed messages and configuration key names may be reported."""


def require(condition, message):
    if not condition:
        raise PreflightError(message)


def command_json(command):
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=45)
    except (OSError, subprocess.TimeoutExpired):
        raise PreflightError('Docker/Compose is unavailable or timed out') from None
    require(result.returncode == 0, 'Docker/Compose check failed; inspect configuration privately')
    try:
        return json.loads(result.stdout)
    except (ValueError, TypeError):
        raise PreflightError('Docker/Compose did not return valid JSON') from None


def check_env_file(path):
    path = path.expanduser().resolve()
    require(path.is_file(), 'Deployment environment file must be a regular file')
    require(not path.is_relative_to(ROOT.resolve()), 'Keep the populated deployment environment file outside the checkout')
    require(stat.S_IMODE(path.stat().st_mode) & 0o077 == 0,
            'Deployment environment file must not grant group/other access (use mode 0600)')
    return path


def check_configuration(config):
    services = config.get('services', {})
    require(all(name in services for name in ('mongo', 'core', 'caddy', 'backup')), 'Missing production Compose service')
    core, caddy = services['core'], services['caddy']
    image = core.get('image', '')
    match = IMAGE.fullmatch(image)
    require(match is not None, 'Core image must use a reviewed sha-<commit> tag, optionally pinned with @sha256:<digest>; main is refused')
    require(core.get('environment', {}).get('SPRING_PROFILES_ACTIVE') == 'prod', 'Core must use only the prod profile')
    environment = core.get('environment', {})
    demo = str(environment.get('HOSTED_DEMO_ENABLED', 'false')).lower()
    require(demo in ('true', 'false'), 'HOSTED_DEMO_ENABLED must be true or false')
    if demo == 'true':
        recipients = str(environment.get('HOSTED_DEMO_EMAIL_ALLOWLIST', '')).split(',')
        require(all(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._%+\-]*@[A-Za-z0-9](?:[A-Za-z0-9.\-]*[A-Za-z0-9])?\.[A-Za-z]{2,}', address.strip()) for address in recipients),
                'HOSTED_DEMO_EMAIL_ALLOWLIST requires nonempty exact email addresses, without wildcards')
    else:
        for key in ('TWILIO_ACCOUNT_SID', 'TWILIO_AUTH_TOKEN', 'VAPID_PUBLIC_KEY', 'VAPID_PRIVATE_KEY', 'VAPID_SUBJECT'):
            require(bool(str(environment.get(key) or '').strip()), f'{key} is required outside hosted demo')
    for key in ('SENDGRID_API_KEY', 'MAIL_FROM_PLATFORM', 'SENDGRID_WEBHOOK_PUBLIC_KEY'):
        require(bool(str(environment.get(key) or '').strip()), f'{key} is required before release, including hosted demo')
    require(not any(service.get('ports') for name, service in services.items() if name != 'caddy'),
            'Only Caddy may publish production ports')
    require(not any(name in services for name in ('seed', 'local-hosts', 'minio')), 'Local proof services are forbidden in a release')
    for service in services.values():
        for key, value in service.get('environment', {}).items():
            require(not isinstance(value, str) or not re.search(r'REPLACE|CHANGE_ME|CHANGEME', value, re.I),
                    f'Unreplaced placeholder in {key}')
    backup = services['backup'].get('environment', {})
    for key in ('BACKUP_AGE_RECIPIENT', 'BACKUP_S3_BUCKET', 'BACKUP_S3_ACCESS_KEY', 'BACKUP_S3_SECRET_KEY'):
        require(bool(backup.get(key)), f'{key} is required before release')
    require(str(backup['BACKUP_AGE_RECIPIENT']).startswith('age1'), 'BACKUP_AGE_RECIPIENT must be a public age recipient')
    require(backup.get('BACKUP_S3_PREFIX', '').endswith('/') and backup['BACKUP_S3_PREFIX'] != '/',
            'BACKUP_S3_PREFIX must be nonempty and end with /')
    require(not any('BACKUP_AGE_IDENTITY' in service.get('environment', {}) for service in services.values()),
            'The private backup identity must not be stored in the normal deployment configuration')
    addresses = [network.get('ipv4_address') for network in caddy.get('networks', {}).values() if network.get('ipv4_address')]
    require(len(addresses) == 1, 'Caddy must have one fixed trusted IPv4 address')
    try:
        address = str(ipaddress.IPv4Address(addresses[0]))
    except ValueError:
        raise PreflightError('Caddy IPv4 address is invalid') from None
    require(core.get('environment', {}).get('TRUSTED_PROXY_PATTERN') == re.escape(address),
            'TRUSTED_PROXY_PATTERN must match only the exact escaped Caddy IPv4 address')
    mongo = services['mongo'].get('environment', {})
    passwords = [mongo.get(key) for key in ('MONGO_INITDB_ROOT_PASSWORD', 'MONGODB_PASSWORD', 'MONGO_BACKUP_PASSWORD')]
    require(all(passwords) and len(set(passwords)) == 3, 'Mongo root, application and backup passwords must be distinct')
    return image, match.group(2)


class ModuleScripts(HTMLParser):
    def __init__(self):
        super().__init__()
        self.sources = []

    def handle_starttag(self, tag, attributes):
        attrs = dict(attributes)
        if tag == 'script' and attrs.get('type') == 'module':
            self.sources.append(attrs.get('src', ''))


def check_dist(directory):
    require(directory.is_absolute(), 'SPA dist directories must use absolute paths')
    directory = directory.resolve()
    require(directory.is_dir() and not directory.is_relative_to(ROOT / 'deploy/local'),
            'SPA mount must be a built release directory, never deploy/local proof fixtures')
    index = directory / 'index.html'
    require(index.is_file(), 'SPA dist directory is missing index.html')
    require(index.resolve().is_relative_to(directory), 'SPA index.html must remain inside its dist directory')
    html = index.read_text(encoding='utf-8')
    require('Fictional local deployment fixture' not in html and 'AgilityHub local deployment proof' not in html,
            'Local proof HTML cannot be deployed as an SPA')
    parser = ModuleScripts()
    parser.feed(html)
    require(bool(parser.sources), 'SPA index.html must load a built JavaScript module')
    for source in parser.sources:
        url = urlsplit(source)
        require(not url.scheme and not url.netloc and bool(url.path), 'SPA module must be a local built asset')
        asset = (directory / unquote(url.path).lstrip('/')).resolve()
        require(asset.is_relative_to(directory) and asset.is_file() and asset.suffix == '.js',
                'SPA module is missing, outside its dist directory or not compiled JavaScript')
    digest, count = hashlib.sha256(), 0
    for file in sorted(directory.rglob('*')):
        require(not file.is_symlink(), 'SPA release artifacts must not contain symlinks')
        if file.is_file():
            digest.update(file.relative_to(directory).as_posix().encode() + b'\0')
            file_digest = hashlib.sha256()
            with file.open('rb') as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                    file_digest.update(chunk)
            digest.update(file_digest.digest())
            count += 1
    return {'sha256': digest.hexdigest(), 'files': count}


def check_spas(config):
    mounts = config['services']['caddy'].get('volumes', [])
    selected = {}
    for target in DIST_TARGETS:
        matches = [mount for mount in mounts if mount.get('target') == target]
        require(len(matches) == 1 and matches[0].get('type') == 'bind' and matches[0].get('read_only') is True,
                f'{target} must have exactly one read-only bind mount')
        selected[target] = Path(matches[0]['source'])
    require(len({path.resolve() for path in selected.values()}) == 3, 'Each SPA must have its own dist directory')
    return {target: check_dist(path) for target, path in selected.items()}


def check_image(image, pinned_digest, expected_digest, inspect):
    require(bool(pinned_digest or expected_digest),
            'Pin IMAGE_TAG with @sha256:<digest> or pass --expected-digest from the reviewed publication')
    if expected_digest:
        require(DIGEST.fullmatch(expected_digest) is not None, 'Expected image digest must be sha256:<64 lowercase hex characters>')
    require(not pinned_digest or not expected_digest or pinned_digest == expected_digest,
            'Pinned image digest does not match the reviewed expected digest')
    require(isinstance(inspect, list) and len(inspect) == 1, 'Expected exactly one locally available Core image')
    metadata = inspect[0]
    image_id = metadata.get('Id', '')
    require(DIGEST.fullmatch(image_id) is not None, 'Docker did not return an immutable image ID')
    digests = sorted({value.split('@', 1)[1] for value in metadata.get('RepoDigests', [])
                      if value.startswith(REPOSITORY + '@') and DIGEST.fullmatch(value.split('@', 1)[1])})
    require(bool(digests), 'Core image has no registry digest; pull the reviewed release image explicitly first')
    for expected in (pinned_digest, expected_digest):
        require(not expected or expected in digests, 'Local image does not match the reviewed registry digest')
    return {'reference': image, 'imageId': image_id, 'registryDigests': digests,
            'platform': metadata.get('Os', '') + '/' + metadata.get('Architecture', '')}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', type=Path, default=Path(os.environ.get('DEPLOY_ENV_FILE', '/etc/agilityhub/core.env')))
    parser.add_argument('--expected-digest', help='Registry digest recorded from the reviewed green CI image publication')
    args = parser.parse_args(argv)
    try:
        require(os.environ.get('DEPLOY_LOCAL') != '1', 'DEPLOY_LOCAL is forbidden for release preflight')
        env_file = check_env_file(args.env_file)
        command = ['docker', 'compose', '--env-file', str(env_file), '--profile', 'ops', '-f', str(ROOT / 'deploy/compose.prod.yaml')]
        config = command_json(command + ['config', '--format', 'json'])
        image, pinned = check_configuration(config)
        spas = check_spas(config)
        metadata = check_image(image, pinned, args.expected_digest, command_json(['docker', 'image', 'inspect', image]))
        print(json.dumps({'result': 'PASS', 'image': metadata, 'webArtifacts': spas,
                          'scope': 'Local release inputs only; remote providers, DNS, backup restore and migration still need evidence'}, indent=2))
        return 0
    except PreflightError as error:
        print('Release preflight failed: ' + str(error), file=sys.stderr)
        return 2
    except (OSError, ValueError, TypeError, KeyError):
        # File paths and expanded Compose errors can contain private values. Never echo them.
        print('Release preflight failed: unreadable or malformed deployment configuration/artifact', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
