#!/usr/bin/env python3
"""Insert verified final command results and literal log tails without touching organizer content."""
from pathlib import Path
import json

base = Path('roadmap/evidence/E8-T05')
results = json.loads((base/'final-results.json').read_text())
assert results and all(r['exit'] == 0 for r in results)
p = Path('roadmap/tasks/E8-T05.md')
s = p.read_text()
summary = (base/'53-test-summary.log').read_text()
assert 'surefire: tests=3700, failures=0, errors=0, skipped=0' in summary
assert 'failsafe: tests=1519, failures=0, errors=0, skipped=0' in summary
intro = '''Final clean build: **3,700 unit tests + 1,519 integration tests = 5,219**, zero failures, errors or skips. Coverage and architecture gates passed without changing their thresholds. The Docker booking smoke passed against the durable adapters. Fresh OpenAPI generation is byte-identical. No CI or production deployment is claimed.

The XML totals, one line per changed executable test class, T-12/T-13 method inventory and calendar coverage are in `roadmap/evidence/E8-T05/53-test-summary.log`. `LifecycleCurlIT` in the clean log contains all six requested curl/TCP responses and the approved document (`firstMonth=2000 EUR`, `followingMonths=1000 EUR`, `history=[]`, `cancelledBookings=[]`; IDs truncated). `LifecycleIT` records the OPEN → CONSUME → REFUND → EXPIRE → ADJUST movements and separately asserts all four cancellation records, rollback and pack refund. The retired null/default and in-memory adapter diff is `roadmap/evidence/E8-T05/adapter-removal.diff`.

**Observed red tests:** log 16 found missing executable PACK_ADJUSTED/MEMBER_PLAN_CHANGED audit coverage; log 37 saw T-13-07 return MEMBER_LEAVING instead of INACTIVITY_PERIOD when both applied; log 48 saw after-leave training accepted (201 instead of 422) and both family cancellation cases select the wrong owner's dog. Logs 49 and 51 pass the corrected assertions. The T-13-03 fixed-month boundary test closes the branch gap found by clean coverage in log 46.

**Earlier failed attempts (retained, never overwritten):**

'''
failures = {
2:'Report whitespace → corrected.',
8:'Default Maven cache was read-only → used the task cache under /private/tmp.',
9:'Initial PACK_ADJUSTED audit enum compile error → resolved in the inherited partial implementation.',
11:'Leave reason list required a typed map → corrected.',
14:'Two tests still expected old 422 state-conflict statuses → aligned with CATALEG_ERRORS rule 0/E50 (409).',
15:'Default Maven cache was read-only → reused the task cache.',
16:'Missing pack/plan audit covers and two stale status assertions → added executable coverage and corrected statuses.',
17:'Deleted in-memory adapters still referenced by demo/training code → migrated callers.',
18:'BookingEligibility owner accessor typo → corrected.',
19:'Projection parentheses compile error → corrected.',
21:'Eager inactivity adapter introduced a Spring dependency cycle → deferred the application-service lookup.',
22:'Overview lifecycle dependency introduced a second cycle → deferred the views lookup.',
23:'Incomplete legacy plan fixtures → populated real plan fields.',
24:'Old BillingRun fixture constructor and contract snapshot → updated to current fields.',
25:'LeaveController retained the removed STUB constant → removed it.',
27:'Foreign persistence import, response fixture and legacy leave errors → application DTO boundary and current response/error contracts.',
28:'Invented notification variable rejected by parity → used the existing localized admin_text field.',
29:'Three notification snapshots were stale → regenerated authorized variants.',
30:'Route served flags/count and curl JWT audience were stale → restored guards, 58-route inventory and clubs-app audience.',
31:'Legacy pack/census fixtures, demo pack preparation, signup allocation order, notification parity and OpenAPI snapshot → corrected.',
36:'Concurrent targeted Maven run collided with clean target deletion → all subsequent Maven checks run sequentially.',
37:'T-13-07 red: leave won over inactivity → reordered lifecycle eligibility.',
38:'Same concurrent target deletion affected compilation → sequential runs thereafter.',
39:'Nullable leaveSource enum emitted a bare null reference → used the existing nullable-reference customizer.',
41:'Annotation-only nullable schema attempt still failed → applied the shared customizer and regenerated.',
43:'Four stale contract assertions (S13 list stubs, export/member metadata and leaving error) → aligned with real operations.',
46:'All 5,215 tests passed but calendar branches were 79.17% versus 80% → tested start-month moves across the fixed-month boundary.',
48:'All three training owner regressions failed → owner-based date guard and cancellation selection.'}
for number, reason in failures.items():
    log = next(base.glob(f'{number:02}-*.log'))
    intro += f'- `{log.as_posix()}`: {reason}\n'
intro += '''
Successful intermediate checks remain in their numbered logs (10/12/20/26/32–35/40/42/44/45/49/50/51/54). Log 47 exited 0 with `-DskipTests` and ran no tests; it is explicitly excluded from validation evidence. Logs 01/03–07 retain the earlier architecture blocker and diff checks, superseded by organizer ruling E90 and this implementation.

**Final commands:** the Maven cache override is necessary because the sandbox cannot write the default `~/.m2` cache. Both heavy commands used the shared host lock; the smoke log includes its expected wait. Tails below are the literal final 40 log lines (or the entire output when shorter), after redacting complete hashes/credential-shaped values.

'''
for r in results:
    log=base/r['log']
    tail='\n'.join(log.read_text().splitlines()[-40:])
    intro += f"Command: `{r['command']}`\n\nExit code: **{r['exit']}**. Full output: `{log.as_posix()}`.\n\n```text\n{tail}\n```\n\n"
a=s.index('### Evidence\n')+len('### Evidence\n'); b=s.index('### Assumptions',a)
s=s[:a]+'\n'+intro+s[b:]
a=s.index('**Status:');b=s.index('\n\n',a)
s=s[:a]+'''**Implementation complete (2026-10-07); awaiting organizer verification.** Resumed the partial E8-T05 implementation already on main under E90. Final clean verification, smoke, snapshot comparison and diff/scope checks all passed. Exactly one roadmap task was handled; no git write command was run.'''+s[b:]
assert len(s.encode())<120_000
p.write_text(s)
print('Final report written:', len(s.encode()), 'bytes; organizer section preserved.')
