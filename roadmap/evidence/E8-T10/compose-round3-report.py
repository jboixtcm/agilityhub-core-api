#!/usr/bin/env python3
"""Append the scoped report with literal completed-command tails, never organizer edits."""
from pathlib import Path

folder = Path('roadmap/evidence/E8-T10')
task = Path('roadmap/tasks/E8-T10.md')
commands = dict(line.split(': ', 1) for line in (folder / 'commands.txt').read_text().splitlines() if ': ' in line)
entries = [
    ('Final host-locked clean verification', '81-clean-verify.log'),
    ('Fresh XML test summary', '82-test-summary.log'),
    ('Suppression-policy regressions', '74-policy-green.log'),
    ('Public fixed-release availability', '77-advisory-releases.log'),
    ('Real notification claims, all write-fence cases and architecture rules', '78-notification-claims-green.log'),
    ('Final packaged dependency audit', '83-final-dependency-audit.log'),
    ('Published baseline CI, not final-commit CI', '86-baseline-ci-handoff.log'),
    ('Pinned evidence secret scan', '85-evidence-secret-scan.log'),
]
for title, filename in [
    ('Full-range whitespace, scope and evidence', '87-handoff.log'),
    ('Roadmap handoff status', '88-status.log'),
    ('Final report and handoff integrity', '89-final-handoff.log'),
]:
    if (folder / filename).exists():
        entries.append((title, filename))

report = '''### Round 3 report

2026-10-08. **E8-T10 only**, from clean `f063fcd`, addressing exactly the two E96 review points. Dependencies E8-T06 and E8-T08 remain verified. No git writes or changes to other tasks, ROADMAP, or Organizer verification. Round 2 is retained; its dependency follow-up and notification-claim limitation are resolved below. Final-commit CI remains the publisher/organizer handoff, not a result available before publication.

#### Files changed
- `src/main/java/com/agilityhub/core/clubs/messaging/persistence/NotificationRepository.java`: shared deterministic claim ordering for both due and accepted deliveries.
- `src/test/java/com/agilityhub/core/shared/persistence/TenantWriteTrackingIT.java`: two real notification-claim regressions against a held maintenance transaction.
- `bin/security-scan-suppressions.json`, `bin/security-scan-test.py`, `src/test/java/com/agilityhub/core/arch/ArchitectureTest.java`: bounded exact-version exception, expiry/matching controls, and a guard against the affected view-rendering feature.
- `CHANGELOG.md`, this task, generated `roadmap/STATUS.md`, append-only `roadmap/MESSAGES.md`, and this task's evidence plus release/scope/report helpers.

#### Rules and tests implemented
1. **E96 point 1, dependency audit.** The cause is a newly reported vulnerability, not point 6's future-date rejection. Pinned Trivy reproduces `CVE-2026-47890` against `org.springframework:spring-webmvc:6.2.19` (`70`, exit 1). The previously existing XsltView exception covers a different CVE. [Spring's advisory](https://spring.io/security/cve-2026-47890/) identifies SSE view-fragment rendering, rates it LOW, and lists 7.0.9 as the public fix and 6.2.20 as enterprise-only. Live [Maven Central metadata](https://repo.maven.apache.org/maven2/org/springframework/spring-webmvc/maven-metadata.xml) still ends the public 6.2 line at 6.2.19 (`77`). The existing scan policy therefore permits the exact-CVE/package/version exception recorded today and expiring **2026-12-07**. Scanner severity, failure thresholds, inventory checks and future-date rejection remain unchanged. `test_E8_T10_round3_point1_sse_finding_has_bounded_exact_version_exception` fails before the entry (`72`) and passes with it, asserting expiry and rejection of a different version, package or CVE. `ArchitectureTest.E8_T10_round3_point1_noSseViewFragments` keeps Spring MVC/WebFlux view rendering and ModelAndView out of production dependencies; this is a preventive guard, not a claim of a reproduced application exploit. The final packaged scan reports the exception visibly and exits 0.
2. **E96 point 2 / R-18-17, T-18-16 / S11 R-11-09.** Both real `claimDue` and `claimAccepted` paths now order by `createdAt`, then unique `_id`. Existing TenantWriteTracking reads only the selected row's tenant from the same Mongo transaction snapshot before registration, and records the returned row after mutation. Thus a lock on another matching tenant is never acquired by this single-row claim. No generic tracking/fence behavior was disabled. `T_18_16_round3_point2_realNotificationClaimIgnoresOtherTenantMaintenance(boolean)` exercises both actual repository methods, with two eligible tenants, identical timestamps and reverse insertion order. While one tenant holds its real reset lock, the other is claimed successfully; the held row and sequence remain unchanged, only the selected tenant advances once, no active registration remains, and the held tenant can still reset. Lease token/deadline and nullable/prior acceptance fields are checked from the real returned Notification. Before the fix both variants surface WriteConflict (`73`: two errors, not assertion failures); after it both pass together with all previous fence controls (`78`).

#### Assumptions
- This is the existing suppression policy's temporary exception, not a dependency upgrade or removal of the finding. A public patched dependency must replace it before expiry; the existing Boot 4 / Framework 7 follow-up remains necessary. The vendor/scanner severity disagreement does not lower the scanner gate.
- Claim ordering now chooses oldest notification creation first, with `_id` breaking ties. Both the tracker and the mutation use this total order in one transaction snapshot. Provider acceptance, lease ownership, retry limits, tenant-scoped direct claims and mutation counting remain unchanged.
- Internal persistence behavior only: no API schema or endpoint change, so no OpenAPI snapshot change is required. No new catalog item, UI flow, cached business value, form version, money calculation or local-time/DST rule. UTC instants use the injected test clock. No user data or credentials were added to evidence; all fixture addresses are fictional `@example.test` values.

#### Questions / catalog proposals
No catalog proposal. **Follow-up to @organizer:** adopt a public patched Framework release before 2026-12-07 and remove the exact-version exceptions; include this SSE advisory with the existing XsltView upgrade work. The final commit does not exist until the external publish script runs after this session. Verify that commit's entire CI run, including both image architectures; the read-only baseline query below is not final-commit evidence.

#### Evidence
Every run uses a unique retained log; exact commands are also in `commands.txt`. The runner captures exits, enforces a 3,600-second subprocess timeout, and sanitizes credential/hash patterns and trailing whitespace. The full Maven run passes through the required host lock. Report tails are the literal last 40 lines (or the entire shorter output) of those retained files. All earlier rounds' logs remain byte-identical.

Earlier attempts:
- `70-dependency-audit-red.log`, exit 1: real packaged scan blocks CVE-2026-47890; add the policy-permitted bounded exception after checking the vendor and public releases. The scanner's mirror fallback succeeded automatically.
- `71-notification-claims-red.log`, exit 1: test compilation required `throws Exception` for the existing held-transaction helper; add it before running the behavior regression.
- `72-policy-red.log`, exit 1: the new policy regression fails for the absent CVE entry, while the other ten tests pass.
- `73-notification-claims-red.log`, exit 1: both real claim methods exhaust retries with WriteConflict against the unrelated tenant's maintenance lock; add shared total ordering.
- `76-advisory-releases.log`, exit 1: the vendor website returns HTTP 403 to urllib. Read the official advisory through web.run instead; `77` explicitly distinguishes that manual source assessment from its live Central query.
- `75-baseline-ci.log`, `80-baseline-ci-jobs.log`, `84-baseline-ci-final.log`, exits 0: preliminary read-only observations while the published baseline was still running; final observation below supersedes them.
- `79-dependency-audit-green.log`, exit 0: preliminary packaged scan after the exception; the final scan below uses the fresh clean build.

'''

totals = [line for line in (folder / '82-test-summary.log').read_text().splitlines()
          if line.startswith(('surefire:', 'failsafe:'))]
assert len(totals) == 2
report = report.replace('#### Files changed', '**Final clean test totals:** `' + '`; `'.join(totals) + '`.\n\n#### Files changed', 1)

for title, filename in entries:
    lines = (folder / filename).read_text().splitlines()
    assert lines[-1] == 'exit 0', filename + ' did not pass'
    tail = '\n'.join(lines[-40:])
    report += f'**{title}.** Command: `{commands[filename]}`. Exit: `0`. Full output: `{folder / filename}`. Literal last {min(40, len(lines))} lines:\n\n```text\n{tail}\n```\n\n'

report += '''**Final checklist:** reviewed the whole diff and new evidence helpers against both numbered corrections and the session checklist. The notification fixtures use the actual persisted Notification, actual repository claims and real Mongo transactions, with the other tenant's lock held throughout. No HTTP/mock contract was invented or assertion relaxed. Scope and full-task-range whitespace checks preserve organizer text and other tasks. The full clean build and final dependency audit are local evidence; the publisher's subsequent full CI run remains explicitly unverified here.

'''
text = task.read_text()
before, organizer = text.split('## Organizer verification', 1)
before = before.split('### Round 3 report', 1)[0].rstrip()
task.write_text(before + '\n\n' + report + '## Organizer verification' + organizer)
assert task.stat().st_size < 120_000
print('Round 3 report composed with exact commands, exits and literal tails; organizer section preserved')
