#!/usr/bin/env python3
"""Temporarily remove infrastructure fixes; restore every source even after a failing test."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[3]
BASE = ROOT / 'src/main/java/com/agilityhub/core'
changes = {
    'shared/api/RateLimitFilter.java': [
        ('return Route.ANONYMOUS;', 'return null;'),
        ('return Route.WEBHOOK;', 'return null;'),
        ('return Route.SIGNED_FILE;', 'return null;'),
        ('return Route.HANDOFF;', 'return null;'),
        ('if (route == Route.TOKEN &&', 'if (false && route == Route.TOKEN &&'),
        ('if (route == Route.HANDOFF &&', 'if (false && route == Route.HANDOFF &&'),
    ],
    'configuration/SecurityBaselineConfiguration.java': [
        ('headers.contentSecurityPolicy(csp -> csp.policyDirectives(com.agilityhub.core.shared.application.ContentSecurityPolicies.API));', '// Counterfactual: API CSP absent.'),
    ],
    'platform/api/ClubCorsConfigurationSource.java': [
        ('return targetClub.isEmpty() || targetClub.equals(originClub);', 'return true;'),
    ],
    'shared/api/RequestTraceFilter.java': [
        ('MDC.put("traceId", traceId(request));', 'traceId(request);'),
    ],
    'configuration/LogPrivacy.java': [
        ('for (Pattern pattern : EXCLUSIONS)', 'for (Pattern pattern : List.<Pattern>of())'),
    ],
    'configuration/SentryPrivacyConfiguration.java': [
        ('return safe;', 'return event;'),
    ],
    'configuration/MongoTimeoutConfiguration.java': [
        ('.maxSize(settings.maxPoolSize())', '.maxSize(101)'),
        ('.timeout(settings.operationTimeout().toMillis(), TimeUnit.MILLISECONDS)', '.timeout(0, TimeUnit.MILLISECONDS)'),
    ],
}
originals = {}
try:
    for relative, replacements in changes.items():
        path = BASE / relative
        original = path.read_text()
        originals[path] = original
        amended = original
        for old, new in replacements:
            assert old in amended, f'Counterfactual target absent: {relative}'
            amended = amended.replace(old, new)
        path.write_text(amended)
    command = ['./mvnw', '-q', '-Dtest=AnonymousRateLimitsTest,RequestTraceFilterTest,LogPrivacyTest,MongoTimeoutConfigurationTest', 'test']
    print('COUNTERFACTUAL COMMAND ' + ' '.join(command), flush=True)
    result = subprocess.run(command, cwd=ROOT)
    print('COUNTERFACTUAL MAVEN EXIT ' + str(result.returncode), flush=True)
    integration = ['./mvnw', '-q', '-DskipTests=false', '-Dit.test=SecurityHardeningIT,CorsIT', 'test-compile', 'failsafe:integration-test', 'failsafe:verify']
    print('COUNTERFACTUAL COMMAND ' + ' '.join(integration), flush=True)
    integration_result = subprocess.run(integration, cwd=ROOT)
    print('COUNTERFACTUAL INTEGRATION EXIT ' + str(integration_result.returncode), flush=True)
    # A nonzero result is necessary; the report must also inspect each expected failed assertion.
    if result.returncode == 0 or integration_result.returncode == 0:
        raise SystemExit('Counterfactual unexpectedly passed')
finally:
    for path, original in originals.items():
        path.write_text(original)
    print('RESTORED all temporarily changed production sources', flush=True)
