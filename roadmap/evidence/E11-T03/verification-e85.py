#!/usr/bin/env python3
"""Final E85 verification: clean CPU-limited tests, current image and complete mutation scores."""
import runpy
from pathlib import Path
old = runpy.run_path(str(Path(__file__).with_name('verification.py')))
run, lock, image = old['run'], old['LOCK'], old['IMAGE']
run(85, 'clean-verify-e85', [lock, 'env', 'JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=2', './mvnw', '-q', 'clean', 'verify'])
run(86, 'test-summary-e85', ['python3', 'roadmap/evidence/E11-T03/test-summary.py'])
committed = '/private/tmp/e11-security-tools/openapi-committed.json'
run(87, 'openapi-first-e85', ['bin/openapi-snapshot'])
run(88, 'openapi-first-e85-cmp', ['cmp', committed, 'docs/openapi/openapi.json'])
run(89, 'openapi-second-e85', ['bin/openapi-snapshot'])
run(90, 'openapi-second-e85-cmp', ['cmp', committed, 'docs/openapi/openapi.json'])
run(91, 'image-build-e85', [lock, 'docker', 'build', '-t', image, '.'])
run(92, 'image-scan-e85', ['bin/security-scan', 'image', image])
run(93, 'dependency-rootfs-e85', ['bin/security-scan', 'rootfs', 'target'])
run(94, 'compose-positive-e85', [lock, 'bin/deploy-smoke', '--image-tag', 'e11-security-local', '--security-only'])
run(95, 'broken-image-build-e85', ['docker', 'build', '-t', 'ghcr.io/jboixtcm/agilityhub-core-api:e11-security-broken', '--file', '-', '.'], input='FROM '+image+'\nENTRYPOINT ["/bin/false"]\n')
run(96, 'compose-negative-e85', [lock, 'bin/deploy-smoke', '--image-tag', 'e11-security-broken', '--security-only'], expected='nonzero')
run(97, 'pitest-e85', [lock, './mvnw', '-q', '-Pmutation', 'test-compile', 'org.pitest:pitest-maven:mutationCoverage'])
run(98, 'mutation-summary-e85', ['python3', 'roadmap/evidence/E11-T03/mutation-summary.py', '--log', '97-pitest-e85.log'])
