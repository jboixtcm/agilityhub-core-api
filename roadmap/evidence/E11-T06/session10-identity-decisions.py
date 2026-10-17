#!/usr/bin/env python3
"""Session 10: the executor's decisions for the 18 identity rows the staged tests do not kill (`221` probe), joined
with the 30 rows `221` shows killed by the staged identity tests (decisions-identity-killed.json) into
decisions-identity.json. Each reason was checked against the source cited in it."""
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent / 'pit/session10'
rows = json.loads((HERE / 'slice-identity-rest.json').read_text())
T = 'test: CompromisedPasswordsSurvivors4Test#'
D = {
    0: "reason: equivalent — for an `imp` token SecurityConfiguration's authorities converter (lines 87-90) already yields exactly [ROLE_MEMBER], so the incoming JwtAuthenticationToken has the same Jwt, name and authorities as the one line 40 installs; removing the call changes nothing a downstream check reads.",
    1: "reason: equivalent — ImpersonationService.validate (line 124) refuses unless the token account's membership memberId equals the grant's impersonatedMemberId, and MeController's membership is that same account's, so the fallback `census.displayName(membership.memberId())` repeats the lookup that just returned empty; Optional.empty gives the same `account.name()`.",
    2: "reason: equivalent — the mutant replaces `return false` (InterruptedException branch) with `return false`.",
    3: "reason: logging only — `warn()` only writes the 'check unavailable' WARN line; the fail-open `false` (A2) is unchanged.",
    4: "reason: equivalent — the mutant replaces `return false` (unavailable/timeout branch) with `return false`.",
    5: "reason: logging only — `warn()` only writes the 'check unavailable' WARN line; the fail-open `false` (A2) is unchanged.",
    # Rows 6/7 (indexes 98/101) are the `finally` copy on the normal-return path (`222` and `223` left 98 SURVIVED even
    # with the timeout and interrupted-lookup tests): 98 is equivalent there, 101 is killed by the completed-lookup test.
    6: "reason: equivalent — index 98 is the null check of the finally copy javac emits on the normal-return path (before the catch handlers at 122+): there pending was assigned before get() returned, so it is never null and already done; the original skips cancel because it is done, the mutant skips it because it is non-null.",
    7: T + 'T_01_09_aCompletedLookupIsNotCancelled',
    8: "reason: lock call — TeamWriteLock.lock (InstructorRepository.lock) only upserts team_write_locks with sequence+1, a document nothing reads; it serialises this invitation with the club's other team writers (R-17-09) and is not observable single-threaded (the tenant write counter it bumps is bumped by the invitation's own writes too).",
    9: "reason: result discarded — the lambda's Set<String> only flows back through IdentityTransactions.run to `run`, which ignores it and prints a fixed line; roles.grant has already run.",
    10: "reason: equivalent — `invalid(...)` records HANDOFF_INVALID and always throws ApiException(HANDOFF_INVALID) (lines 74-77), so the replaced return value is never produced.",
    11: "reason: PIT cannot evaluate (MEMORY_ERROR, never credited) — `i++` -> `i--` on a CRLF steps back onto the CR and loops forever until the minion's heap is exhausted; the CRLF behaviour is asserted by LearnCsvTest.T_01_14_csvSupportsBomCrLfQuotedNamesEscapedQuotesAndMultilineFields, which never ends under this mutant.",
    12: "reason: equivalent — the preview Account's passwordHash is never read: after a preview row the account carries that row's learnUserId, so a later row with the same email is either already linked (ALREADY_IMPORTED / PLATFORM_ADMIN_GRANTED, lines 79-82) or an EMAIL_CONFLICT (line 70), never the hash-based reason of line 83; the dry-run report is identical.",
    13: "reason: unreachable with real data (defensive filter) — no production writer stores an Account status other than ACTIVE (Account.Status.BLOCKED/MERGED/ERASED are never assigned in src/main; AccountService, SignupIdentityService and LearnImportService create ACTIVE accounts), so `status()==ACTIVE` is always true.",
    14: "reason: result discarded — the only caller, ConsoleAdministrators.remove (line 54), ignores the returned Membership and answers snapshot().",
    15: "reason: unreachable with real data (defensive check) — Membership.Status.ERASED is only ever read, never written (no ERASED assignment or update in src/main), so `m.status()!=ERASED` is always true.",
    16: "reason: equivalent — every caller uses `scope(...)` as a try-with-resources resource (lines 143, 162, 198); Java skips close() on a null resource, and the replaced `() -> { }` closes nothing.",
    17: "reason: unreachable with real data (defensive filter) — no production writer stores an Account status other than ACTIVE (see MagicLinkService:53), so the filter never removes the account.",
}
assert len(rows) == len(D) == 18
rest = {row['identity']: D[index] for index, row in enumerate(rows)}
with (HERE / 'decisions-identity-rest.json').open('x') as handle:
    json.dump(rest, handle, indent=1, ensure_ascii=False)
joined = json.loads((HERE / 'decisions-identity-killed.json').read_text())
assert not set(joined) & set(rest)
joined.update(rest)
assert len(joined) == 48
with (HERE / 'decisions-identity.json').open('x') as handle:
    json.dump(joined, handle, indent=1, ensure_ascii=False)
print(f'{len(joined)} identity decisions: {sum(v.startswith("test:") for v in joined.values())} tests')
