#!/usr/bin/env python3
"""Write E8-T05's final Executor report with literal log tails; the Organizer verification section is kept byte for byte."""
from pathlib import Path

base = Path('roadmap/evidence/E8-T05')
task = Path('roadmap/tasks/E8-T05.md')
text = task.read_text()
start = text.index('## Executor report\n')
end = text.index('## Organizer verification')


def tail(name, lines=40):
    return '\n'.join((base / name).read_text(errors='replace').splitlines()[-lines:])


report = f'''## Executor report

**Status: implementation complete (2026-10-07), awaiting organizer verification.** This session resumed the partial E8-T05 implementation already on `main` (E90 direction, 07-10 organizer message). An independent read-only review of the whole task diff (`git diff 2e31d8a`) found five real defects and one missing contract declaration. All are fixed below, and each fix has a new test that was seen failing without it (log 58). Then the final clean verify, the E5 smoke, the OpenAPI byte comparison and `git diff --check` all passed. Only this roadmap task was handled; no git write command was run.

### Branch / commits

Working tree on `main` only. The publish script commits and pushes after the session. No CI result is claimed.

### Files changed

Inherited partial implementation (already on `main`, listed in `roadmap/evidence/E8-T05/files-changed.txt`):
- `clubs.bookings.application`: **new adapters** `PaymentsPackBalanceAdapter` (E5-T02 `PackBalancePort` → `payments.application.PackBalanceService`) and `CensusInactivityAdapter` (E5-T02 `InactivityPort` → census inactivity services). It also adds `CensusBookingCancellations`, which implements the census-owned `BookingCancellationPort`, waitlist included. **Deleted** `ports/InMemoryPackBalances` and `ports/InMemoryInactivity` (they were `@Profile({{"local","test"}})`) and their null defaults (diff: `adapter-removal.diff`). Tests and seeders now store real E8 documents.
- `clubs.training.application.CensusTrainingCancellations` (`TrainingCancellationPort`) and `clubs.activities.application.CensusActivityCancellations` (`ActivityCancellationPort`): **new**. The census declares the three ports in **new** `clubs/census/application/ports/{{BookingCancellationPort,TrainingCancellationPort,ActivityCancellationPort,LifecycleCancellation}}`.
- **Moved ports:** `InactivityFeePort` and `LeaveBillingPort` moved from `payments.application.ports` to `shared.application` with unchanged signatures. `InactivityFeeService` and `LeaveBillingService` (census) implement them, and `BillingPortDefaults` lost them. `PackBalanceOpeningPort` stays in payments and is implemented by `PackBalanceService` itself.
- `payments.application`: `PackBalanceService`, `ManualUpfrontPayments`, `MemberPlanChangeService`, `PackViews`, `PackAuditLoader`. `payments.api`: the pack, upfront-payment and `MemberPlanChangeController` routes.
- `clubs.census`: `InactivityPeriodService`, `InactivityScheduler`, `InactivityFeeService`, `LeaveRequestService`, `LeaveScheduler`, `LeaveBillingService`, `InactivityCalendar` (pure domain), lifecycle views/lists/transactions/audit loaders, `MemberBookingEligibility`, readers (`CensusReferences`, `CensusListProjection`) on the E8 fields, and the controllers.
- `clubs.common.application.LifecycleSavedViewSeeder` / `SavedViewService` (the protected «Baixes previstes» view); `configuration.LifecycleDashboardConfiguration` (`PendingRequestsQuery`).
- Messages and seeds ca/es/en; the authorized N-11b `pack_remaining` catalog row (step 15); fixtures; snapshots; `docs/openapi/*`; `CHANGELOG.md`.

This session (review fixes):
- `payments/application/PackBalanceService.java`: `PACK_EMPTY` (a zero balance) only for a PACK plan. The pack that expires first is chosen in the service (`firstToExpire`), no longer by relying on the repository's sort.
- `clubs/census/application/InactivityPeriodService.java`: `closeForLeave` only shortens (or closes at) the leave month and never lengthens. An override edit sets `deadlineOverridden` on the existing decision and never creates one. A PATCH of an `ACTIVE` period refuses only a *different* `fromMonth`/`comments`.
- `clubs/activities/application/{{CensusActivityCancellations,ActivityRegistrationService}}.java` and `clubs/bookings/application/CensusBookingCancellations.java`: only activities and waitlist entries still to come are counted or cancelled, as classes and trainings already were.
- `clubs/census/api/{{InactivityController,LeaveController}}.java`: declared errors are completed (`MEMBER_ERASED` on the three `/me` writes, `READ_ONLY` + `INACTIVITY_OVERLAP` on both PATCH routes); `docs/openapi/openapi.json` + `docs/openapi/CHANGELOG.md` updated.
- Tests: `PackBalanceServiceTest` (+2, the mock no longer sorts), `LifecycleIT` (+4), `E8ContractIT` (erased-member checks on the three `/me` writes), `BookingFixtures.openPack` (puts the holder on the pack plan, as R-08-17 requires), `BookingsIT` (the second dog of the S15 P5c case gets its own pack).
- `CHANGELOG.md`; `roadmap/evidence/E8-T05/56…66-*.log`, `summarize_final.py`, `write_final_report.py`; this report.

### Rules and tests implemented (R-xx-nn / T-xx-nn)

- **R-12-23/24/28, R-05-18b · T-12-07, T-12-22 (pack half), T-12-31 (pack half):** inclusive expiry `openedOn + validityMonths − 1 day`, idempotent per payment, the first-expiring qualifying pack (`T_12_07_whenTwoPacksQualifyTheOneThatExpiresFirstIsConsumed`), `PackLowBalance` once, refund only of an unreturned CONSUME and also on an EXPIRED balance, `PACK_NEGATIVE`, reopening only with a new `expiresOn`, module off = no-op, a monthly member never refused with `PACK_EMPTY` (`T_12_22_aMonthlyMemberWithAnOldPackBooksWithoutPackRules`), manual payment (`AMOUNT_EXCEEDS_DUE`, `PARTIAL`), the audits `PACK_ADJUSTED`/`UPFRONT_PAYMENT_RECORDED`/`MEMBER_PLAN_CHANGED`, the 40 % pack→membership entry.
- **R-13-01/03/04 · T-13-01…T-13-04** (`InactivityCalendarTest`, no Spring); **R-13-08/11/07 · T-13-05…T-13-07** (`LifecycleRulesTest`, no Spring/Mongo).
- **R-13-02/04/05/06 · T-13-08…T-13-14, T-13-26** (`LifecycleIT`; family isolation, rollback, all four owners; past sessions untouched: `T_13_09_aPeriodThatStartsAtOnceLeavesPastSessionsAlone`; override: `T_13_12_anOverriddenEditMarksTheExistingApprovalAndNeverInventsOne`; `T_13_13_anActivePeriodAcceptsItsUnchangedStartButNotANewOne`).
- **R-13-09/10/12/13/14/15/16 · T-13-15…T-13-21** (incl. `T_13_16_aLeaveShortensAnActivePeriodButNeverLengthensIt`), `TrainingIT` T-13-07/09/16 owner cases.
- **R-13-17…20 · T-13-22…T-13-25**, `LifecycleCurlIT` (six HTTP calls over TCP with real JWTs), `E8ContractIT` (routes served, guards, erased member), `AuditContractTest`, `EventCatalogContractTest`, `MessageParityTest`.
- T-13-27/28 (scheduler jobs) and T-13-29…32 (front) belong to E8-T06 / the web, as the task says.

### Evidence

**Final verification (this session):**

Command: `/Users/jordib/Dropbox/Documents/SOFTWARE_CANIC/05-desenvolupament/roadmap-kit/mac/heavy.sh ./mvnw -q clean verify > roadmap/evidence/E8-T05/63-clean-verify.log 2>&1; echo "exit $?"`

Exit code: **0**. Full output: `roadmap/evidence/E8-T05/63-clean-verify.log` (hashes redacted with `sanitize_evidence.py`). With `-q` a green build prints no summary. The XML totals are in `roadmap/evidence/E8-T05/64-test-summary.log` (`python3 roadmap/evidence/E8-T05/summarize_final.py 63 0`, exit 0): **surefire tests=3702, failures=0, errors=0, skipped=0 · failsafe tests=1523, failures=0, errors=0, skipped=0**. That log also has one line per test class the task adds or changes (for example `LifecycleIT: tests=28`, `PackBalanceServiceTest: tests=7`, `E8ContractIT: tests=71`, `TrainingIT: tests=26`, `BookingsIT: tests=20`, `WaitlistIT: tests=18`, `AttendanceIT: tests=19`, `ArchitectureTest: tests=17`, `AuditContractTest: tests=1`, `EventCatalogContractTest: tests=2`, `MessageParityTest: tests=1`, all with 0 failures/errors/skips), the full T-12/T-13 method inventory and the calendar coverage (line 100 %, branch 91.67 %). The coverage and architecture gates are part of this `verify` and passed with unchanged thresholds.

```text
{tail('63-clean-verify.log')}
```

Command: `/Users/jordib/Dropbox/Documents/SOFTWARE_CANIC/05-desenvolupament/roadmap-kit/mac/heavy.sh bin/e5-smoke > roadmap/evidence/E8-T05/65-e5-smoke.log 2>&1; echo "exit $?"`

Exit code: **0**. Full output: `roadmap/evidence/E8-T05/65-e5-smoke.log`.

```text
{tail('65-e5-smoke.log')}
```

Command: `cp docs/openapi/openapi.json target/e8t05-fixed/openapi.before.json && bin/openapi-snapshot > roadmap/evidence/E8-T05/66-openapi-snapshot.log 2>&1; echo "snapshot exit $?"; cmp target/e8t05-fixed/openapi.before.json docs/openapi/openapi.json; echo "cmp exit $?"`

Exit codes: snapshot **0**, `cmp` **0** (byte-identical, no output). The snapshot itself changed in this session (declared errors, see `docs/openapi/CHANGELOG.md`), regenerated in log 59 before the final verify. Last 40 lines of `66-openapi-snapshot.log` (cut to 300 characters per line here; the log has them complete):

```text
{chr(10).join(l[:300] for l in tail('66-openapi-snapshot.log').splitlines())}
```

Command: `git diff --check; echo "diff-check exit $?"` → `diff-check exit 0` (no output).

**Evidence to return:**
- **curl sequence** (`LifecycleCurlIT`, log 63 lines 5033–5040): `POST /api/v1/me/inactivity-periods -> 201` · `POST /api/v1/inactivity-periods/[id truncated]/decision -> 200` · `GET /api/v1/members/s08-m-laura/overview -> 200` · `POST /api/v1/me/leave-requests -> 201` · `POST /api/v1/leave-requests/[id truncated]/decision -> 200` · `GET /api/v1/members?filter=displayStatus:eq:LEAVE_SCHEDULED -> 200` · `PASS S13 curl sequence`.
- **InactivityPeriod after approval** (same block): `state=APPROVED feeSnapshot={{firstMonth={{amountMinor=2000, currency=EUR}}, followingMonths={{amountMinor=1000, currency=EUR}}}} history=[] cancelledBookings=[]`. The curl case has no bookings inside, so `T_13_09_approvalCancelsAllFourKindsAndRefundsOnlyTheOwnersPack` asserts the four `cancelledBookings` instead. `T_13_13_extensionKeepsTheFeeSnapshotAndAppendsHistory` asserts `history` (1 entry).
- **PackBalance cycle** (log 63 lines 5265–5269): `OPEN delta=10` → `CONSUME -1` → `REFUND +1` → `EXPIRE 0` → `ADJUST +1`.
- **Removed doubles:** `roadmap/evidence/E8-T05/adapter-removal.diff`.
- **`MemberBookingEligibility` for T-13-07** (`LifecycleRulesTest`): 2026-10-30 allowed · 2026-11-03 `INACTIVITY_PERIOD` · 2027-01-02 allowed; the leave day allowed, the next day `MEMBER_LEAVING`.

**Review fixes, red → green (this session):** `58-review-fixes-red.log` shows the production files temporarily restored to `HEAD` (tests unchanged): exit 1, and **all six new tests fail** — `T_12_07_whenTwoPacksQualify…:64`, `T_12_22_aMonthlyMember…:79`, `T_13_09_aPeriodThatStartsAtOnce…:101`, `T_13_12_anOverriddenEdit…:126`, `T_13_13_anActivePeriodAccepts…` (403 READ_ONLY on an unchanged `fromMonth`), `T_13_16_aLeaveShortens…:184`. With the fixes restored they pass (`57-review-fixes-green.log`, exit 0). The `E8ContractIT` erased-member addition covers behaviour that already existed (`mutableMe()` first). It did not fail before and is coverage, not a regression test.

**Earlier attempts (retained, never overwritten):**
- `01`/`04`/`06-dependency-direction.log`: the architecture contradiction that blocked the task → organizer ruling E90.
- `08`/`15`: the default Maven cache was read-only in the earlier sandbox → an earlier session used a writable copy under `/private/tmp`. This session ran plain `./mvnw` with the default cache.
- `09`/`11`/`17`/`18`/`19`/`25`: compile errors while migrating the doubles → fixed.
- `14`/`16`: old 422 expectations for state/duplicate conflicts, and audit covers missing for `PACK_ADJUSTED`/`MEMBER_PLAN_CHANGED` (the 07-10 CI red) → statuses aligned with CATALEG_ERRORS rule 0, executable `@AuditCovers` tests added.
- `21`/`22`: Spring dependency cycles → lazy lookups.
- `23`/`24`/`27`/`28`/`29`/`30`/`31`: fixtures, contract snapshots, notification parity and route inventory brought up to date.
- `36`/`38`: concurrent Maven runs collided on `target/` → only sequential runs since.
- `37`: T-13-07 red, leave won over inactivity → reordered.
- `39`/`41`: a nullable enum reference broke the OpenAPI schema → shared nullable customizer.
- `43`: four stale contract assertions → aligned.
- `46`: 5,215 tests green, but calendar branches were at 79.17 % → T-13-03 boundary test.
- `48`: training owner regressions, red → owner-based selection (green in `49`).
- `51`/`52`/`54`: green verify, smoke and snapshot of the previous session (superseded by this session's code changes). `55`: a verify cut short when that session ended (no result).
- `56-clean-verify.log`: green (exit 0) before the review fixes, superseded.
- `60-clean-verify.log`: exit 1, 13 E5 failures. The first version of the pack fix ignored even usable packs outside a PACK plan, which broke fixtures and the E6/E7 demo seed that open packs for members on other plans. The fix was narrowed to the reviewed defect (only `PACK_EMPTY` depends on the plan), and `BookingFixtures.openPack` now puts the holder on the pack plan.
- `61-pack-gate-targeted.log`: exit 1, `BookingsIT.S15_P5c…` booked a second dog with no pack under a PACK plan → that dog gets its own pack. `62-pack-gate-targeted.log`: the ten affected classes green (exit 0).
- Log 47 exited 0 with `-DskipTests` and is not validation evidence.

### Assumptions

1. `CATALEG_ERRORS` rule 0/E50 overrides the task's older status list: `INACTIVITY_INVALID_STATE`, `LEAVE_ALREADY_REQUESTED`, `LEAVE_ALREADY_SCHEDULED`, `LEAVE_INVALID_STATE` are **409** (the catalog lists them so). The other codes keep the task's statuses.
2. A pack opens only when its PACK upfront payment is paid in full. A `PARTIAL` manual payment opens nothing until it is complete. Expiry keeps the unused number visible, but an EXPIRED balance is never usable, and a refund into it does not revive it (B11).
3. **Pack vs plan (this session):** S08 R-08-17 says that with a `MONTHLY` plan «no s'aplica res». The code refuses with `PACK_EMPTY` only when the owner's plan is `PACK`. A *usable* pack (a manual/gift one included, step 1 «migration, gift») is still consumed whatever the plan, as before. Making packs strictly plan-gated would change the E5 fixtures and the E6/E7 demo seed, which open packs for members on other plans (log 60). See the question below.
4. A leave closes an `ACTIVE` period at the leave month only when that does not lengthen it (`finishReason = LEAVE`, no N-18c). A period ending earlier keeps its end and finishes by itself.
5. `POST /members/{{id}}/plan-change` applies now. An optional `effectiveMonth` must be the current club month (no deferred-plan state exists). It writes a DUE entry and leaves `nextInvoiceDate` alone. Reactivation charges no entry fee.
6. N-18b's warning for disabled cancellation uses the existing `admin_text`. N-28 uses its existing variables for the planned, denied and cancelled variants. No new parameter, event, notification, error code or audit action is introduced beyond E8-T01's and S14's lists.
7. The schedulers stay plain services (`executeDue`, `startDue`, `finishDue`, `expireStale`). The job wiring is E8-T06.

### Questions / catalog proposals

- @organizer (nonblocking, already in `MESSAGES.md` 07-10): sync the task's status wording with E50; model proposal for `WaitlistCancelReason.INACTIVITY` and `LEAVE` (S13 §13-11); copy the authorized N-11b `pack_remaining` row to the source and web; record that N-28's cancellation variant is triggered by `LeaveCancelled`.
- @organizer (nonblocking, new entry in `MESSAGES.md`): does a usable pack apply to a member whose plan is not `PACK` (a gift, or what is left after a pack → membership change)? R-08-17 reads «no», but the E5 fixtures and the demo seed assume «yes». Assumption 3 keeps «yes» for usable packs and never refuses a non-PACK plan.
'''

text = text[:start] + report + '\n' + text[end:]
assert len(text.encode()) < 120_000, len(text.encode())
task.write_text(text)
print('report written,', len(text.encode()), 'bytes; organizer section preserved')
