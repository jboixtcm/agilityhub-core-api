# Word feedback API handoff — 2026-10-30

API source frozen for coordinator review, clean verify and CI. No commit/push/deployment or live merge performed here. The web remains in progress.

## Source and checks

`api-source-sha256.json` fingerprints every changed/new API source, test and contract. `word-api.patch` contains that complete API delta. The existing CI/Caddy/deployment changes are intentionally outside this feature manifest and remain intact.

- `./mvnw -q -Dtest=OpenApiSnapshotTest,CensusIT,AttendanceIT,PackBalanceServiceTest,AttendanceTransitionsTest,AttendanceSheetServiceSurvivorsTest,BookingCancellationServiceSurvivorsTest,CalendarIT#Word*,ArchitectureTest,ArchitectureRulesTest -Dopenapi.snapshot.update=true test`: exit 0, complete log `14-api-final.log`.
- Final contract refinement: an admin-added dog's chip may be absent until recorded. `./mvnw -q -Dtest=OpenApiSnapshotTest,CensusIT#Word* -Dopenapi.snapshot.update=true test`: exit 0, 15 tests, log `19-api-contract-final.log`.
- `bin/openapi-snapshot --from-build`: exit 0. `docs/openapi/openapi.json` is generated.
- Report counters: `api-verification.json`. The full CensusIT run had 67 passing tests; the final refinement reran its three Word tests.
- Coordinator owns `./mvnw clean verify` and independent final review. Do not run Maven concurrently in this checkout.

## Behavior

- Attendance corrections are ADMIN or an instructor assigned to that class. Existing time windows remain. NOTIFIED → PENDING can undo a notice before the presence window; presence requires the usual window (admin override remains audited). Restoration takes the same class seat lock/transaction, rejects a full class, revalidates a future booking's dog/member eligibility, reuses its booking id and appends attendance history. Refunded pack sessions are re-consumed with append-only movements and idempotent correction/refund cycles; empty balance aborts the transaction.
- ADMIN POST /members/{id}/dogs creates ACTIVE immediately under the existing ACTIVE member, no signup approval/payment side effect. Idempotency required, impersonation refused. Optional handlerIsMember keeps a customary handlerName independent of owner-name comparison.
- ADMIN GET merge-preview and POST merge require two explicit identities and both versions. Source dogs, future/current class/training bookings, live waiting/holds, current/future activity registrations, balances and eligible membership ownership move. Original member stays LEFT, not billable, immutable through census edits, with mergedIntoMemberId; target links original sources in mergedMembers. Past bookings, attendance, invoices, movements and audit records remain. Both source and survivor get explicit merge audit entries.
- Preflight blocks different access accounts, two memberships, staff as source, different family groups, pending signup/dog/readmission, pending/active lifecycle processes, outstanding upfront source payments, and duplicate active activity registrations. These need resolving rather than silently discarding history. Do not merge the six real migration cases without the team's decision.
- Member/dog writes share Mongo documents with booking eligibility locks, so booking creation racing a merge conflicts and retries. All participant operations bind TenantContext. Version checks and idempotency prevent a stale preview/retry from applying another merge.
- Day grid accepts optional staff state ACTIVE/CANCELLED/DRAFT; members cannot request the staff-only filter.

## Published verification plan (coordinator; synthetic data only)

1. Verify anonymous/member/instructor/impersonation refusals on new admin routes and cross-club ids returning no data. Add one synthetic dog under a synthetic active member, replay its Idempotency-Key and confirm one dog, ACTIVE, correct owner, preserved handler flag, no new account/signup/payment.
2. Preview a synthetic imported duplicate without an access account against a synthetic active survivor. Include a dog, future booking and historical invoice. Confirm once; replay the key; check one merge, source LEFT with history link, target dog/future ownership and both audit entries. Check historical invoice and attendance unchanged. Attempt a stale preview and two-account/activity conflicts, asserting no partial movement. Never exercise this against real migration cases.
3. Create a controlled class/pack booking. Mark NOTIFIED early, restore PENDING; verify original booking id, one refund + one consumption and appended audit/history. Retry the save key. Refill the seat with another QA booking and confirm CLASS_FULL leaves both bookings/pack/sheet unchanged. Repeat with an empty refunded pack and verify atomic refusal. An unrelated instructor must get FORBIDDEN; the assigned instructor and admin must succeed within their windows.
4. Verify DRAFT daily ring and instructor views keep the selected filter. Verify global training limit plus per-ring overrides (clear an override to inherit), using QA rings/slots; never leave temporary overrides on real rings.

## Configuration/data handoff

`/Users/jordib/Dropbox/Documents/SOFTWARE_CANIC/ops/configure-word-feedback.py` defaults to read-only preparation; `--apply` is for the coordinator's exclusive window. Requires a private `--output` path. It first freezes each inherited dog permission to its current effective boolean, then sets level capacities 5 / code A 6 and grantsFreeTraining false; finally classes.defaultCapacity=5. General training.capacityPerRingSlot and ring overrides stay unchanged (default 1). Every completed step is persisted and a full readback verifies the intended result. Rerun safely after an interruption. If a frozen readmission or concurrent edit prevents an update, stop before altering the levels and inspect the private evidence. Existing platform-level legacy inheritance remains compatible but is disabled in this club's level configuration; the dog UI only offers manual yes/no.

Both code and configuration are still pending the single coordinated activation. No hosted behavior is claimed from local tests.
