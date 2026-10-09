#!/usr/bin/env python3
"""E11-T06 counterfactual: reinstate each defect, run its regression, restore every source byte-for-byte.

Usage (from anywhere): 26-counterfactual.py NN-name — writes the Maven output of each Java group to NN-name-maven-G.log.
Each group edits its files, runs its tests, and restores them in a finally block; stale Surefire reports of the selected
classes are deleted first, so a group whose reverted source does not compile cannot read an older run's results. The final
check compares SHA-256 digests of every touched file with the digests taken before any edit.
"""
import hashlib
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
HANDLER = 'src/main/java/com/agilityhub/core/shared/api/ApiExceptionHandler.java'
FILTER = 'src/main/java/com/agilityhub/core/shared/api/RateLimitFilter.java'
ASYNC = 'org.springframework.web.context.request.async.'

# Test classes by simple name; their packages are kept apart from the method names.
PACKAGES = {'SentryCaptureTest': 'com.agilityhub.core.configuration', 'LogPrivacyTest': 'com.agilityhub.core.configuration',
            'RateLimitIT': 'com.agilityhub.core.identity.api', 'AnonymousRateLimitsTest': 'com.agilityhub.core.shared.api'}
JAVA_GROUPS = [
    ('A: steps 1, 2, 3 and 7 reverted', {
        HANDLER: [
            # Step 1: no explicit capture of handled 5xx (the Sentry resolver never sees them).
            ('        if (sentry != null) {\n            var event', '        if (false) {\n            var event')],
        FILTER: [
            # Step 2: the old account key (strip + lower, no NFC).
            ('fingerprint(normalizeAccountEmail.apply(username))', 'fingerprint(username.strip().toLowerCase(java.util.Locale.ROOT))'),
            # Step 7: the payment routes fall back to the shared infrastructural quotas.
            ('        if (path.startsWith("/webhooks/stripe/")) { return Route.STRIPE_WEBHOOK; }\n', ''),
            ('        if (method.equals("GET") && path.matches("/api/v1/checkout-sessions/[^/]+")) { return Route.CHECKOUT_STATUS; }\n', '')],
        'src/main/java/com/agilityhub/core/configuration/LogPrivacy.java': [
            # Step 3: one separator between digit groups.
            ('\\\\+?\\\\d(?:[ .()-]{0,2}\\\\d){8,14}', '\\\\+?\\\\d(?:[ .()-]?\\\\d){8,14}')],
    }, {'SentryCaptureTest': 'T_14_30_handled500ReachesTransportOnceWithResponseTraceAndNoPrivateData',
        'LogPrivacyTest': 'T_14_30_phoneFormatsAreExcludedFromConsoleAndSentry',
        'RateLimitIT': 'T_01_15_unicodeEquivalentAccountsShareQuotaAcrossIps',
        'AnonymousRateLimitsTest': 'T_12_15_E11_paymentRoutesHaveIndependentQuotas'}),
    ('B: step 1 review-pass fixes reverted (answered 500s not captured; SSE ends captured)', {
        HANDLER: [
            ('        if (exception.code() == ErrorCode.INTERNAL_ERROR) {', '        if (false) {'),
            ('        if (exception instanceof ' + ASYNC + 'AsyncRequestNotUsableException\n'
             '                || org.springframework.web.util.DisconnectedClientHelper.isClientDisconnectedException(exception)) {',
             '        if (false) {'),
            (' || exception instanceof ' + ASYNC + 'AsyncRequestTimeoutException)', ')'),
            # A committed SSE stream that times out gets a JSON error appended again.
            ('        if (exception instanceof ' + ASYNC + 'AsyncRequestTimeoutException\n                && response.isCommitted()) {',
             '        if (false) {')],
    }, {'SentryCaptureTest': 'T_14_30_handled500ReachesTransportOnceWithResponseTraceAndNoPrivateData'}),
    ('C: step 7 review-pass fix reverted (the signed remittance-file download has no quota)', {
        FILTER: [('\n                || path.matches("/api/v1/remittances/files/[^/]+/[^/]+")', '')],
    }, {'AnonymousRateLimitsTest': 'T_01_15_E11_anonymousFamiliesRefuseWithRetryAndEventThenRecover'}),
]
DEPLOY = {
    # Step 4: Core takes the URI override again.
    'deploy/compose.prod.local.yaml': [
        ('      SPRING_PROFILES_ACTIVE: prod\n', '      SPRING_PROFILES_ACTIVE: prod\n      SPRING_DATA_MONGODB_URI: ${LOCAL_MONGODB_URI:?Set LOCAL_MONGODB_URI with URL-encoded credentials}\n')],
    # Step 5: system.views back in the inventory.
    'deploy/backup/backup.py': [("name in ('system.profile', 'system.views')", "name == 'system.profile'")],
    # Step 6: pre-deploy verification with the deployment environment (writer credentials).
    'docs/DEPLOY.md': [('   DEPLOY_ENV_FILE=/etc/agilityhub/recovery.env bin/restore-mongo --verify\n', '   bin/restore-mongo --verify\n')],
}
POM = {'pom.xml': [('                                <param>com.agilityhub.core.payments.*</param>\n', '')],
       # Step 8 (review pass): the mutation identity read `indexes`/`blocks` as text, so two mutants of one line collided.
       'bin/mutation-gate': [("""tuple(mutation.findtext(key, '') for key in IDENTITY) + tuple(
                tuple(node.text for node in mutation.iter(tag)) for tag in ('index', 'block'))""",
                              """tuple(mutation.findtext(key, '') for key in IDENTITY + ('indexes', 'blocks'))""")]}


def digest(path):
    return hashlib.sha256((ROOT / path).read_bytes()).hexdigest()


def apply(edits):
    saved = {}
    for path, replacements in edits.items():
        original = (ROOT / path).read_bytes()
        saved[path] = original
        text = original.decode()
        for old, new in replacements:
            assert text.count(old) == 1, f'{path}: expected exactly one {old!r}'
            text = text.replace(old, new)
        (ROOT / path).write_bytes(text.encode())
    return saved


def restore(saved):
    for path, original in saved.items():
        (ROOT / path).write_bytes(original)


def run(label, command):
    print(f'\n=== {label}: {" ".join(command)}', flush=True)
    result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True)
    print(f'exit {result.returncode}', flush=True)
    return result


def java_group(number, label, edits, tests, failures_seen):
    reports = {name: ROOT / 'target/surefire-reports' / f'TEST-{PACKAGES[name]}.{name}.xml' for name in tests}
    for report in reports.values():
        report.unlink(missing_ok=True)
    started = time.time()
    saved = apply(edits)
    try:
        selection = ','.join(f'{name}#{method}*' for name, method in tests.items())
        result = run(f'Java regressions, group {label}',
                     ['./mvnw', '-q', '-Dsurefire.failIfNoSpecifiedTests=false', f'-Dtest={selection}', 'test'])
        (ROOT / f'roadmap/evidence/E11-T06/{sys.argv[1]}-maven-{number}.log').write_text(result.stdout + result.stderr)
    finally:
        restore(saved)
    for name, method in tests.items():
        report = reports[name]
        if not report.exists() or report.stat().st_mtime < started:
            print(f'NO FRESH REPORT: {name} (compilation failed?)')
            failures_seen[f'{label[:1]} {name}'] = False
            continue
        cases = [case for case in ET.parse(report).getroot().iter('testcase') if case.get('name').startswith(method)]
        failed = [case for case in cases if case.find('failure') is not None or case.find('error') is not None]
        for case in cases:
            outcome = 'FAILED' if case in failed else 'passed'
            detail = next((node.get('message', '') for node in case if node.tag in ('failure', 'error')), '')
            print(f'{outcome}: {name}.{case.get("name")} {next(iter(detail.splitlines()), "")[:160]}')
        failures_seen[f'{label[:1]} {name}'] = bool(failed) and all(case.find('error') is None for case in cases)


def main():
    touched = sorted({path for _, edits, _ in JAVA_GROUPS for path in edits} | set(DEPLOY) | set(POM))
    before = {path: digest(path) for path in touched}
    failures_seen = {}
    for number, (label, edits, tests) in enumerate(JAVA_GROUPS, 1):
        java_group(number, label, edits, tests, failures_seen)
    saved = apply(DEPLOY)
    try:
        result = run('Deployment regressions with steps 4 and 6 reverted',
                     ['python3', '-m', 'unittest', '-v',
                      'deploy.test_round2.DeploymentReviewTest.test_E11_T06_production_mongo_wiring_without_uri_override',
                      'deploy.test_round2.DeploymentReviewTest.test_E11_T06_predeploy_verification_selects_recovery_identity'])
        print(result.stderr[-2500:])
        failures_seen['steps 4 and 6'] = result.stderr.count('... FAIL') == 2
        result = run('Backup inventory regression with step 5 reverted',
                     ['docker', 'run', '--rm', '--network', 'none', '-v', f'{ROOT}/deploy/backup:/review:ro',
                      '--entrypoint', '/opt/backup/bin/python', 'agilityhub-backup:2', '-m', 'unittest', 'discover',
                      '-v', '-s', '/review', '-p', 'test_backup.py'])
        print(result.stderr[-2500:])
        failures_seen['step 5'] = re.search(r'test_E11_T04_inventory_excludes_internal_cluster_keys \(.*\) \.\.\. FAIL',
                                            result.stderr) is not None and result.stderr.count('... FAIL') == 1
    finally:
        restore(saved)
    saved = apply(POM)
    try:
        result = run('Mutation scope and identity regressions with step 8 reverted', ['python3', 'bin/mutation-gate-test.py'])
        print(result.stderr[-2000:])
        failures_seen['step 8'] = re.search(r'test_E11_T06_pit_selects_every_critical_package_without_class_exclusions \(.*\) \.\.\. FAIL',
                                            result.stderr) is not None
        failures_seen['step 8 gate identity'] = re.search(
            r'test_E11_T06_two_mutants_of_one_line_differ_by_their_index \(.*\) \.\.\. (FAIL|ERROR)', result.stderr) is not None
    finally:
        restore(saved)
    after = {path: digest(path) for path in touched}
    assert after == before, 'sources were not restored byte-for-byte'
    print('\nRESTORED every touched source byte-for-byte (SHA-256 before == after) for ' + str(len(touched)) + ' files')
    for key, value in failures_seen.items():
        print(f'SUMMARY {key}: {"fails-before" if value else "DID-NOT-FAIL"}')
    return 0 if all(failures_seen.values()) else 1


if __name__ == '__main__':
    sys.exit(main())
