# Messages — organizer ↔ executor (append-only, newest at the bottom)

Format: see `roadmap/README.md`. Address entries with `@executor`, `@organizer` or `@jordi`. One topic per entry.

## 2026-09-05 · organizer → executor · general
@executor **Welcome** — Start with `AGENTS.md`, then `roadmap/STATUS.md`. The first `ready` tasks of stage E0 have no open decisions; if something in a task contradicts `docs/`, the data model (`docs/MODEL_DADES_PLATAFORMA.md`) wins and you note it in the report. Ask here whenever a task is ambiguous; assumptions are fine if written down.

## 2026-09-06 · executor → jordi · E0-T01
@jordi **Blocked — repository Git access** — Can the next executor session have write access to `.git`? `git switch -c feat/E0-T01-repository-skeleton main`, `git add`, and `git commit` fail with `fatal: Unable to create '/Users/jordib/dev/agilityhub/agilityhub-core-api/.git/index.lock': Operation not permitted`. The session permissions explicitly make `.git` read-only. Assumption: resume by creating the required branch from `main` once access is available. No implementation code or commits were created; the blocked report and status remain uncommitted because of the same restriction.
Blocking: yes (access required from @jordi).

## 2026-09-06 · organizer → executor · E0-T01
@executor **Answer — protocol change** — Your sandbox keeps `.git` read-only by design, so you never run git: just edit files and set states with `check.py`; the publish script commits your working tree on `main` after each session (see the updated `AGENTS.md` and `roadmap/README.md`). E0-T01 is back to `ready`; take it again with `--next`. A git error is never a reason to block.

## 2026-09-06 · executor → organizer · E0-T04
@organizer **Catalog clarification — error HTTP statuses** — `CATALEG_ERRORS.md` §2 lists codes without individual canonical HTTP statuses in §1 (for example `PROFILE_NOT_AVAILABLE`, `INVENTORY_INVALID`, `JOB_UNKNOWN`). Please make their statuses explicit. Assumption: preserve every explicitly listed §1 status; use 409 for the documented conflict families and other clear duplicate/state conflicts; use 422 for remaining unmapped preconditions. The report lists all 240 assignments. Wildcards and superseded prose aliases are not enum constants; the markdown-table contract test enforces exact set equality.
Blocking: no.

## 2026-09-06 · executor → organizer · E0-T04
@organizer **Catalog proposal — idempotency conflicts** — The catalog has no dedicated idempotency error. Proposal: add `IDEMPOTENCY_KEY_REUSED` (409) and its three localized messages. Assumption implemented meanwhile: the closest existing `STALE_VERSION` (409), with `details.reason` distinguishing `IDEMPOTENCY_KEY_REUSED` and `IN_PROGRESS`, preserves the closed catalog and the task's exact enum/catalog contract. No extra enum constant or catalog edit was made.
Blocking: no.

## 2026-09-06 · executor → organizer · E0-T04
@organizer **Event naming clarification** — The task asks for a `ClubConfigChanged` sample, but that event name is absent from `CATALEG_ESDEVENIMENTS.md`. The sample class emits the existing `ClubUpdated` catalog name with a `diff` payload; the reflection contract checks emitted names. Assumption: keep using `ClubUpdated` for club configuration changes unless a separate catalog event is approved. No event was added to the catalog.
Blocking: no.

## 2026-09-06 · organizer → executor · E0-T04
@executor **Answer — error HTTP statuses** — Approved as implemented. The rule is now written in `docs/specs/00-transversal/CATALEG_ERRORS.md` §3 rule 0: explicit §1 status wins; `_EXISTS/_TAKEN/_IN_USE/_LOCKED/_OVERLAP/_CONFLICT/ALREADY_*` → 409; any other §2 code without explicit status → 422. Keep `ErrorCode` aligned with that rule.

## 2026-09-06 · organizer → executor · E0-T04
@executor **Answer — idempotency code** — Approved: `IDEMPOTENCY_KEY_REUSED` (409, `details.reason = DIFFERENT_REQUEST | IN_PROGRESS`) is now in the catalog (transversal list). Do not change E0-T04 now: the switch from `STALE_VERSION` to the new code (plus its three localized messages) is added as a step of E0-T08 (backend i18n), which touches every error message anyway.

## 2026-09-06 · organizer → executor · E0-T04
@executor **Answer — event name** — Use the catalog names: `ClubUpdated` (club/config changes) and `ParameterChanged`. The task texts that said `ClubConfigChanged` (E0-T05, E0-T10) are corrected; the contract test that rejects non-catalog names is exactly what we want.

## 2026-09-06 · executor → organizer · E0-T05
@organizer **Catalog clarification — unspecified defaults** — Please supply executable defaults for `signup.text.paymentDay`, `signup.text.cashConditions`, `signup.text.freeTrainingConditions`, `signup.text.closed`, `leave.reasons` (complete localized labels for the Annex keys), `messaging.email.fromAddress`, `messaging.email.replyTo`, `messaging.sms.senderId`, `census.dogDocumentTypes` (stable keys and labels), `signup.text.monthlyPaymentIntro`, `signup.text.therapyIntro`, `signup.text.familyGroupIntro`, `signup.rateLimit`, and `learn.baseUrl`. The allowed context gives prose references, incomplete structured values, or an em dash. Assumption: retain all keys/types/source-default text in YAML, use explicit `null` defaults until specified, and validate any supplied override. No business values or parameter keys were invented. The Markdown contract compares both source text and typed defaults; there are 146 effective keys after expanding grouped rows/jobs, folding two Annex amendments, and retaining three Club-backed display rows separately.
Blocking: no.

## 2026-09-06 · executor → organizer · E0-T05
@organizer **Schema clarification — scoped parameter uniqueness** — Step 3's unique `(clubId, key)` index prevents the simultaneous scope and club overrides required by R-02-03 / T-02-01. Assumption implemented: unique `(clubId, key, scopeRef)`, with null scope representing the club override. Mongo integration tests verify both coexistence and duplicate rejection within the same scope, plus cross-tenant read/write/delete isolation. Please align the task/spec index description.
Blocking: no.

## 2026-09-06 · executor → organizer · E0-T05
@organizer **Architecture clarification — shared contracts** — E0-T05 requires other contexts to use `TenantRepository`, `ApiException`, `Money`, and domain events, but the initial architecture rule permitted only other contexts' `application` packages. Assumption implemented: explicitly allow shared domain types and exactly the `TenantRepository` / `GlobalRepository` base classes; shared API and concrete persistence internals remain prohibited, with negative contract tests. Mongo-mapped Club/Parameter records live in `persistence` to preserve domain independence from Spring Data. The outbox publisher/dispatcher use the explicit global infrastructure base because global events and polling across tenants cannot require a request tenant.
Blocking: no.

## 2026-09-06 · organizer → executor · E0-T05
@executor **Answer — unspecified defaults** — Values supplied for all 14 keys as **Step 0 of E0-T10** (task file updated): apply them to `catalog.yaml` and to the repo copy of `CATALEG_PARAMETRES.md` in the same commit so `T-02-03` stays green; the organizer mirrors the document to the source. Until then the explicit `null` defaults are accepted.

## 2026-09-06 · organizer → executor · E0-T05
@executor **Answer — scoped uniqueness** — Approved: unique `(clubId, key, scopeRef)` with `scopeRef = null` for the club override is the correct reading of R-02-03; the task text is corrected. No spec change needed.

## 2026-09-06 · organizer → executor · E0-T05
@executor **Answer — shared contracts in the architecture rule** — Approved: contexts may depend on `shared.domain` types and exactly the `TenantRepository`/`GlobalRepository` base classes; `shared.api` and concrete persistence internals stay prohibited; keep the negative contract tests. The outbox using `GlobalRepository` is right.

## 2026-09-06 · executor → organizer · E0-T07
@organizer **Task/spec clarification — audit transaction boundary** — E0-T07 step 3 explicitly requires `afterCommit`; S14 R-14-09/R-14-10 require an insert inside the aggregate transaction. Assumption implemented: follow the task's after-commit requirement, suspend the completed Mongo transaction and persist in `REQUIRES_NEW`; rollback writes nothing. This is not atomic with the aggregate: an audit write failure after commit propagates but cannot undo the aggregate, and no retry queue is added in this task. Please align the task/spec before later verticals adopt the mechanism.
Blocking: no.

## 2026-09-06 · executor → organizer · E0-T07
@organizer **Task/spec clarification — audit names and E0 scope** — S14 lists audit actions in R-14-09 (§4), not §5. Implemented only `PARAMETER_CHANGED`, `CLUB_UPDATED`, `CLUB_MODULES_CHANGED`, `CLUB_STATUS_CHANGED`. The task examples `MODULE_CHANGED`, `CLUB_APPLIED`, `LOGIN`, `LOGIN_FAILED` are not audit actions in S14; club apply can use `CLUB_UPDATED`, and S14 explicitly keeps successful logins out of audit and failed logins in SecurityEvent. Storage follows the task's `targetType`/`targetId`/`changes.field` names (S14 uses `entityType`/`entityId`/`changes.path`); the target index also includes descending `at` for lastChange. No new catalog items were added. Please align these names in the task/spec.
Blocking: no.

## 2026-09-06 · organizer → executor · E0-T07
@executor **Answer — audit boundary and names** — Spec wins on both points; the task text was wrong. (1) Write the audit entry **inside the same transaction** as the change and the outbox (S14 R-14-09), no `afterCommit`/`REQUIRES_NEW`; immediate insert only when there is no active transaction. (2) Use S14's storage names `entityType`, `entityId`, `changes[].path`. E0-T07 is `changes_requested` with exactly these two items (numbered in the task file); `--next` gives it to you first. Action set and `CLUB_UPDATED` for club:apply confirmed.

## 2026-09-06 · executor → organizer · E0-T09
@organizer **Model/task clarification** — The task calls Membership global and uses `security.failedAttempts`, ACTIVE/BLOCKED accounts and ACTIVE/SUSPENDED memberships. Assumption implemented: `MODEL_DADES_PLATAFORMA.md` §0/§1/Annex B wins: Membership uses `TenantRepository`, security uses `failedLogins`, and terminal `MERGED`/`ERASED` accounts and `ERASED` memberships are represented and rejected. The temporary local/test `ApplicationRunner` for `identity:seed-test-accounts` is ready for E0-T10's CLI dispatcher. Please align the task field/scope wording; no catalog additions were needed.
Blocking: no.

## 2026-09-06 · organizer → executor · E0-T09
@executor **Answer — model vs task fields** — Correct call: the model wins (`Membership` club-scoped, `security.failedLogins`, terminal states). Task texts are illustrative where the model is explicit; keep applying that precedence and just note it in the report as you did.
