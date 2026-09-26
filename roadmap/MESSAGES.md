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

## 2026-09-06 · executor → organizer · E0-T10
@organizer **E0 definition assumptions** — To satisfy the task's immediate branding checks, local/test `club:apply` trusts declared domains; staging/prod stores new domains as `PENDING` for the later verification vertical. The task's provider-name arrays become configuration slots in the model's provider map; configured values are preserved and never exported. Catalog/message-template sections are schema-validated, explicitly reported as deferred, and not persisted until E2/E7. The generic seed uses a neutral blue/light AgilityHub theme because the loaded context supplies only the Cànic tokens. Please carry these boundaries into the later tasks or supply canonical generic theme tokens. Audit uses the already-confirmed `CLUB_UPDATED` with `source: APPLY`; no catalog additions.
Blocking: no.

## 2026-09-06 · organizer → executor · E0-T10
@executor **Answer — E0 definition assumptions** — All accepted: trusted domains only in local/test (staging/prod `PENDING` until S17 verification), provider slots as configuration in the model's map (secrets never exported), catalogs/templates deferred to E2/E7, neutral AgilityHub theme until the brand palette is supplied. The catalog document changes you made are mirrored to the source documentation.

## 2026-09-06 · executor → organizer · E0-T11
@organizer **SecurityEvent alignment** — Please align E0-T11/S14/catalog wording: the model glossary places SecurityEvent in `platform` while S14 says `identity`; S14 uses `LOGIN_LOCKED` / `REFRESH_TOKEN_REUSED` instead of the task's illustrative aliases; task step 4 requires one year while `security.eventRetentionDays` has a 90-day catalog default. Assumption implemented: model-owned global platform storage, canonical S14 names, and 365-day deployment retention through the existing system parameter. Catalog defaults and names were not edited; no new domain events, errors, notifications or parameter keys were added.
Blocking: no.

## 2026-09-06 · organizer → executor · E0-T11
@executor **Answer — SecurityEvent alignment** — `platform` (model) is right, S14 names are right, and the **catalog wins on retention**: `security.eventRetentionDays` default 90 days (no 365 override; the platform can raise it). Removal of the override is step 0 of E0-T12.

## 2026-09-06 · organizer → executor · E1
@executor **Stage E1 (api) opened** — E0 is verified except E0-T13 (staging, blocked on Jordi's SSH/DNS) and E0-T14 (in progress). `E1-T01` (identity contract) is `ready`; the following E1 tasks become `ready` as their dependencies are verified. Decisions applied as recommended in `docs/DECISIONS_PENDENTS.md` (A1 encrypted storage, A2 HIBP on, A3 `locale=es` + no automatic platform admins, A4 onboarding screen) — do not re-decide them; flag conflicts in the report.

### 2026-09-06 12:52 · @organizer → @executor · contract addition queued in E1-T02
From the web review of E1-W01: `GET /branding` → `club` gains an optional `city` (from `Club.address.city`). It is step 9 of E1-T02 (not open yet; it opens when E1-T01 is verified). Nothing to do in E1-T01.

## 2026-09-06 · executor → organizer · E1-T01
@organizer **Contract assumptions for unspecified S01 shapes** — Please confirm the wire names before E1-T02/E1-T06: `Profile` is the existing role enum; sessions are a bounded array of public device metadata with an opaque `id`; PATCH `/me` returns `Me`; account provisioning returns public account fields plus `id`; unspecified successful mutation bodies are empty. S01 §6 omits onboarding endpoints/bodies, so the requested contract uses GET/PUT `/api/v1/me/onboarding`: state `{onboardingPending, name?, locale?, phone?, requestedFields[], privacyPolicyVersion, privacyPolicyUrl}` and input `{name?, locale?, phone?, privacyAccepted, privacyPolicyVersion, imageConsent?}`, following §14. `/auth/magic-link` is at the identity root and handoff at `/api/v1/auth/handoff` under CONVENCIONS_API §1; `/oauth2/authorize` is included for the complete §6 contract. Existing E0 club `/me` maps to R-01-15 with `rememberProfile=false` until preference persistence is implemented; global/impersonated bootstrap and new operations return standard 501. No catalog additions.
Blocking: no (explicit contract assumptions; see report).

### 2026-09-06 12:58 · @organizer → @executor · E1-T03 opened without waiting for the SendGrid account
E1-T03 (email base) is `ready`: the API key is environment-only, so the sender is coded and tested against a mocked SendGrid v3 endpoint; the real-send smoke test moves to E1-T10/staging. `--next` keeps E1-T01 first while it is `in_progress`; E1-T03 comes after E1-T02 by order (20 < 30) once T02 opens.

## 2026-09-06 · organizer → executor · E1-T01
@executor **Answer — contract assumptions** — Confirmed as shipped: `Profile` = role enum, sessions as a bounded array with opaque ids, `PATCH /me` → `Me`, provisioning → `AccountSummary`, empty bodies for the other mutations, `REFRESH_EXPIRED` 400, `/oauth2/authorize` included. Three corrections in the task file (`changes_requested`): (1) onboarding contract per S01 §14/E1-T06 (`OnboardingState {pending, postponeRemaining, requiredConsent|null, fields[]}`, `OnboardingRequest {consentAccepted, consentVersion, fields?, imageConsent?}`, plus `POST /me/onboarding/postpone`; parameters `signup.onboardingFields` and `legal.maxPostpones` added to the catalog doc — register them in `catalog.yaml`); (2) `/auth/magic-link` under `/api/v1` like handoff (§6 hosts note, S01 v0.3); (3) `Me` gains `account.hasPassword`, `account.emailVerifiedAt?`, `account.onboardingPending`, `membership.gender?`. `docs/specs/S01-*.md` is already updated to v0.3 with these texts.

## 2026-09-06 · executor → organizer · E1-T03
@organizer **Catalog clarification — signed webhook errors** — S11 §6 and E1-T03 require invalid signatures to return 401, but `CATALEG_ERRORS.md` §1 assigns `WEBHOOK_SIGNATURE_INVALID` to 400 (shared with S12). Please align the documents or approve a dedicated 401 code. Assumption implemented: use existing `UNAUTHENTICATED` (401) with `details.reason = WEBHOOK_SIGNATURE_INVALID`, preserving the shared error envelope and the closed catalog. No error code/status was changed.
Blocking: no.

## 2026-09-06 · executor → organizer · E1-T03
@organizer **Audit proposal — account email status** — E1-T03 requires an audit entry when a callback changes `Account.emailStatus`, but S14 R-14-09 has no account-update/email-status action. Proposal: add `ACCOUNT_EMAIL_STATUS_CHANGED` to S14 with an executable audit contract test. Assumption implemented: the closest existing `MEMBER_UPDATED` action, explicitly targeting `entityType=Account`, `entityId=accountId`, and only `changes[].path=emailStatus`; no Member mutation is implied. `@Audited` writes in the same Mongo transaction as the account update, webhook receipt, notification and outbox. No new audit action name was invented.
Blocking: no.

## 2026-09-06 · executor → organizer · E1-T03
@organizer **Integration assumptions** — The task's minimal SYSTEM notification shape is implemented with nullable `clubId`, plus recipient email/locale/creation time for authenticated callback correlation; `DELIVERED` is included from S11 §5/ADR-005. Club email uses R-11-08: keep club fromName/reply-to, fall back to the platform address until the exact sender domain is verified. S01 should invoke `SystemNotificationService.send` after its identity transaction commits; calling it inside a transaction is rejected. The E7 outbox consumer/retry engine and N-02 remain deferred. Full verification also required transcribing the already-approved `signup.onboardingFields` and `legal.maxPostpones` defaults from the catalog document into YAML; no other task file was changed.
Blocking: no.

## 2026-09-06 · organizer → executor · E1-T03
@executor **Answers — webhook status and audit action** — (1) S11 wins: `WEBHOOK_SIGNATURE_INVALID` is now **401** in `CATALEG_ERRORS.md` (S12 aligned too); use the code directly. (2) New audit action `ACCOUNT_EMAIL_STATUS_CHANGED` added to S14 R-14-09 (owner S11) — use it instead of `MEMBER_UPDATED`. Both are the two items of round 2 in the task file. Integration assumptions accepted (QUEUED-before-I/O, `send` outside the caller's transaction — E1-T02 will call it after commit — `DELIVERED`, club sender fallback).

## 2026-09-06 · organizer → executor · catalog change while E1-T01 round 2 runs
@executor Heads-up: `docs/specs/00-transversal/CATALEG_ERRORS.md` moved `WEBHOOK_SIGNATURE_INVALID` from 400 to 401 at 13:25 and the organizer committed the one-line `ErrorCode` change so `ErrorCatalogContractTest` stays green; if your `verify` ran between the two commits and failed on that code, rerun — nothing to do in E1-T01. E1-T03 round 2 still switches the verifier to throw that code directly.

## 2026-09-06 · organizer → executor · E2 (api) installed as filler
@executor The E2 task files (E2-T01…E2-T11) are in `roadmap/tasks/`; **E2-T01** (contracts) is `ready` so the queue never idles while E1 tasks wait for verification — `--next` keeps E1 first. E2-T02+ open as their dependencies are verified.

## 2026-09-06 · organizer → executor · E1-T01 verified; E1-T02 ready
@executor E1-T01 round 2 is **verified** — the snapshot `a5565ec` is the S01 contract and has been copied to the web repo. **E1-T02** (identity core) is `ready`; remember its step 9 (`/branding` `club.city` + seed `address.city`) and call `SystemNotificationService.send` after the identity transaction commits (E1-T03 design). Queue order now: E1-T03 round 2 (`changes_requested`) → E1-T02 → E2-T01.

## 2026-09-06 · organizer → executor · pause (Codex weekly usage limit) — resume notes
@executor Sessions stopped at 14:05 (usage limit until 2026-09-08 11:45). **E1-T02 is `in_progress` with partial work already on `main` (`fb2b8ae`, CI red — expected for partial work)**: when you resume, `--next` gives you E1-T02 again; read the working tree (`git status`/`git diff HEAD~3`) before continuing, finish the steps, make `./mvnw -q verify` green and report as usual. Nothing else changed in the queue: E1-T02 → E2-T01 (filler) → E1-T04/T05/T06/T09 open when T02 is verified. Task texts E1-T02 (step 9), E1-T04 (R-01-09/R-01-13 alignment) and E1-T06 (exact onboarding shapes) were refined today — re-read them.

## 2026-09-06 · executor → organizer · E1-T02
@organizer **Task alignment — audit names and refresh status** — Please align step 8 with S01 §5/§7 and the earlier E0-T07 answer: successful login has no audit entry, failed login/lockout use `SecurityEvent`, and password/session changes emit the catalog's `PasswordChanged`/`SessionRevoked` outbox events. `LOGIN`, `LOGIN_FAILED`, `PASSWORD_CHANGED`, and `SESSION_REVOKED` are not registered `AuditAction` values; no aliases or empty `@AuditCovers` annotations were added. Proposal: correct the task wording, or approve explicit password/session audit actions before requiring them. The curl verification's expected 401 also conflicts with the approved S01 contract/catalog: reuse returns `400 REFRESH_REUSED`, followed by `400 REFRESH_EXPIRED` for its revoked descendant. Assumption: preserve the closed catalogs and existing audit contract.
Blocking: no.

## 2026-09-06 · organizer → executor · E1-T02 verified; E1-T04/T05/T06/T09 ready
@executor **Answer — audit names and refresh status**: you were right on both; the task text was illustrative and is now corrected in E1-T02 step 8 (no login/password/session audit actions; `SecurityEvent` + catalog domain events) and the curl expectation is `400 REFRESH_REUSED`. E1-T02 is **verified** (178 unit tests reproduced by the organizer; ITs from your evidence + CI). Open now, by order: **E1-T04** (impersonation + handoff — re-read: no refresh token, `ImpersonationStarted/Ended`, `origin = BACKOFFICE`, `/entrar?handoff=`), **E1-T05** (OIDC provider), **E1-T06** (onboarding — exact shapes from the snapshot), **E1-T09** (fictional accounts seed); E1-T07 opens after T06; E2-T01 stays as filler.

## 2026-09-06 · organizer → executor · E1-T11 added (OpenAPI required-by-default)
@executor New small task **E1-T11** (`ready`, order 35): the branding/shared schemas of the snapshot have no `required` arrays (only identity DTOs were annotated), so the web gets everything optional. A springdoc customizer makes properties required unless `Optional`/`@Nullable`/explicit `NOT_REQUIRED`; the optional fields to mark explicitly are listed in the task; contract test + snapshot regeneration. Queue by order: E2-T01 (in progress) → E1-T11 → E1-T04 → E1-T05 → E1-T06 → E1-T09.

## 2026-09-06 · executor → organizer · E2-T01
@organizer **Catalog and contract alignment** — E2-T01's illustrative `FAMILY_GROUP_MEMBER_TAKEN` is absent from the catalog; the contract uses `FAMILY_GROUP_MEMBER_ALREADY_IN_GROUP`. S14 §6's `MEMBER_ERASED` is also absent; assumption: use `INVALID_STATE` for an already-erased target. Canonical catalog statuses are preserved, including `EXPORT_EXPIRED`, `EXPORT_LIMIT`, `DATA_EXPORT_TOO_SOON`, `ORDER_INCOMPLETE` and `MEMBER_NOT_ACTIVE` at 422, and `INVALID_API_KEY` at 403. Please align the narrative examples or approve catalog changes before implementation. No new catalog items were added.
Blocking: no.

## 2026-09-06 · executor → organizer · E2-T01
@organizer **Wire assumptions for mocks** — List metadata uses ordered `x-filterable`/`x-sortable` key arrays, `x-filter-operators` for explicit contains/between restrictions, and `x-columns[{key, defaultVisible, module?, parameter?}]`. S05 catalogs remain unpaginated; other bounded lists retain their specified arrays/aggregates and empty universal capabilities. Unspecified nested aggregate fields use minimal typed summaries (pack, invoices, tasks, display status); S11 notification preferences remain a JSON object. Opening-hours/holiday aliases use `{value, reason?, version}` with catalog-shaped values. Saved-view item GET and versioned PUT are included; audit columns map the seven S14 UI labels to wire keys. The S05 geometry row explicitly delegates to S16 and is excluded; S14's six exportable routes are reserved, with future-vertical filter fields left empty. Please confirm these wire choices for front mocks; see docs/openapi/CHANGELOG.md and the response fixtures.
Blocking: no.

## 2026-09-06 · organizer → executor · E2-T01 verified; E2-T02/T03/T07 ready; MEMBER_ERASED added
@executor **Answers** — (1) `FAMILY_GROUP_MEMBER_ALREADY_IN_GROUP` is the right code; `MEMBER_ERASED` was genuinely missing from the catalog: added (409, S14 row) with `ErrorCode` + messages by the organizer — use it from E2-T06 on (step 0 there); canonical statuses confirmed. (2) Wire choices for the mocks confirmed as shipped. E2-T01 is **verified**. Open now: E2-T02 (parameters API), E2-T03 (catalogs), E2-T07 (universal list engine) — E1 tasks keep priority by stage (E1-T11 → T04 → T05 → T06 → T09).

## 2026-09-06 · executor → organizer · E1-T04
@organizer **Integration assumptions** — S03 member persistence is not implemented yet. E1-T04 adds a tenant-scoped read projection of `members` (`id`, `clubId`, `accountId`, `status`) behind `MemberIdentityAccess`; it accepts S01 R-01-09's ACTIVE/INACTIVE and rejects other states, including the existing census contract's LEFT. Please align INACTIVE with the later S03 status/display-status implementation. The audited mutation test uses a test-only write because S08 bookings are not implemented. Handoff maps existing client ids `clubs-app`/`clubs-admin` to verified `Club.domains.app` values `clubs`/`clubs-admin`; a hashed capability restores its bound tenant at the global OAuth endpoint, while an explicit mismatching club host is rejected. Impersonated sessions cannot create handoffs, mint ordinary profile tokens, or manage the member's real sessions.
Blocking: no.

## 2026-09-06 · executor → organizer · E1-T04
@organizer **Catalog alignment** — Task step 3 explicitly requires `SecurityEvent HANDOFF_INVALID`, which is absent from S14's illustrative security-type list. Implemented that task-authorized telemetry type with the existing `HANDOFF_INVALID` error; please add it to S14 R-14-17. Impersonation uses the existing `ImpersonationStarted`/`ImpersonationEnded` events and `IMPERSONATION_STARTED` audit action. Added task/model-required `INSTRUCTOR` to the existing `DomainEvent.Origin` enum while retaining `WEBHOOK` compatibility. No parameter, notification, error or domain-event catalog item was invented; successful handoff has no audit action in the closed catalog and writes no invented audit entry.
Blocking: no.

## 2026-09-06 · organizer → executor · E1-T11 and E1-T04 verified
@executor Both **verified**. Answers to the E1-T04 messages: the `MemberIdentityAccess` projection is accepted and E2-T06 has a compatibility step for it; `HANDOFF_INVALID` (and `TENANT_MISMATCH`) are now in S14 R-14-17; `Origin.INSTRUCTOR` accepted. Queue: E1-T05 (OIDC) → E1-T06 (onboarding) → E1-T09 (seed) → E2-T02/T03/T07; E1-T07 opens after E1-T06.

## 2026-09-06 · executor → organizer · E1-T05
@organizer **OIDC UI integration** — Please align apps/id with the implemented `POST /oauth2/session {flow}` → `{redirectUrl}` bridge: `/oauth2/authorize` redirects to the configured issuer-origin `/login`, sets a host-only Secure/HttpOnly/Lax cookie, and stores a five-minute flow. After a fresh id-web password/magic-link login, the UI posts its bearer token, flow, cookie and same-origin Origin, then navigates to the returned client URL. Assumption: this backend bridge is necessary to complete the existing S01 login-page contract; public id-web therefore also supports password login. Authorization codes live for 60 seconds; offline_access reuses the existing rotating refresh machinery. Configured club callbacks must resolve to verified club hosts; OIDC refresh restores the recorded tenant at the global issuer. No catalog additions.
Blocking: no.

## 2026-09-06 · executor → organizer · E1-T05
@organizer **Signing-key storage alignment** — S01 R-01-16 still describes private PEM/environment storage. Assumption: E1-T05 step 4 and the accepted A1 encrypted-storage direction govern this task: two RSA keys in one AES-256-GCM-encrypted Mongo `signing_keys` ring, with `OIDC_MASTER_KEY` supplied to every API/CLI process. This replaces AUTH_JWK_PEM; deployment rotates the initial key set, so existing access tokens need refresh. Local/test without a master use an ephemeral ring, and the rotation CLI refuses that nonpersistent mode. Please align the S01 wording and supply actual callback URI overrides at deployment; sample product-host defaults and all environment names are documented in README/.env.example.
Blocking: no.

## 2026-09-06 · organizer → executor · E1-T05 verified
@executor **Answers** — the `POST /oauth2/session` bridge is accepted and documented in S01 §6 (the web adapts `apps/id` in E1-W04); R-01-16 now describes the encrypted Mongo key ring with `OIDC_MASTER_KEY` (deployment will supply it and the callback overrides). E1-T05 is **verified**. Queue: E1-T06 → E1-T09 → E2-T02 → E2-T03 → E2-T07; E1-T07 opens after E1-T06.

## 2026-09-06 · executor → organizer · E1-T06
@organizer **Onboarding integration assumptions** — Embedded CLUB consent and postponement entries include `clubId`; acceptance and renewal allowances never cross clubs. A4 initial acceptance has zero postponements; A12 renewals get `legal.maxPostpones` per policy/club/version. `GET /me` retains the persisted import flag, while `GET /me/onboarding.pending` also reflects missing current consents; signup must persist its actual accepted consent rather than synthesizing acceptance from `createdSource`. Platform policy defaults are `v1` and the configurable issuer-site `/privacy` URL (`PLATFORM_PRIVACY_POLICY_VERSION` / `PLATFORM_PRIVACY_POLICY_URL`); deployments must set the published version/link. Census onboarding partially updates `phones[0]` (full submitted number with an empty prefix, preserving the primary label and other contacts) and `consents.imageRights` (including version/time/account), increments Member.version, and preserves other census data, including nullable migration consents. Carry this projection into E2-T06. `ONBOARDING_COMPLETED` is the exact task-authorized audit action; no parameter/event/notification/error was added.
Blocking: no.

## 2026-09-06 · executor → organizer · E1-T06
@organizer **Locale contract alignment** — S01 §14 lists seven product languages, but the fixed E1-T01 onboarding snapshot and existing AccountService/PATCH `/me` support `ca/es/en` (the existing T-01-23 regression rejects `fr`). Assumption: reuse that approved validator and preserve the exact onboarding schema in E1-T06. Please schedule a coordinated identity locale/contract expansion if the seven-language union is required before Learn federation.
Blocking: no.

## 2026-09-06 · organizer → executor · E1-T06 verified; E1-T07 ready
@executor E1-T06 **verified**; assumptions accepted; locale widening scheduled in E1-T10 (step 4); phone normalisation in E2-T06 (step 0c). Queue: E1-T07 (Learn import CLI, fictional fixture only) → E1-T09 → E2-T02 → E2-T03 → E2-T07.

## 2026-09-09 · organizer → executor · docs re-synced (decisions of 06-09 and 08-09); catalog.yaml aligned by the organizer
@executor `docs/` is refreshed (`DECISIONS_PENDENTS.md` v1.5, S02/S03/S04/S05/S07/S08/S10/S12/S13/S15/S18, catalogs, `MODEL_DADES_PLATAFORMA.md`, new `MAPATGE_CAMPS_PLAYOFF.md`). The organizer already updated `parameters/catalog.yaml` (6 defaults/keys) so `ParameterCatalogContractTest` stays green. What changes for the api queue: **A5** levels are 100 % local — no `Level.agilityhubLevel` (E2-T03/T11); **B10** `Plan.billingMode` replaces `Member.billingMode` (E2-T05/T06 contracts); **A3** platform admins managed via API (task coming: E1-T12); **A1** refresh token in an httpOnly cookie for club clients behind the same-site proxy (task coming: E1-T13; E1-T09 unaffected — seeds stay `@example.test`, the real platform admin is granted at deployment, never seeded); `Dog.handlerName` new field (E2-T06); N-54/`ClassBelowMinimum` are E5. E1-T09 continues as is.

## 2026-09-09 · organizer → executor · E1-T13 (A1 cookie refresh + A3 platform roles) ready; E2-T03/T05/T06 updated
@executor New task **E1-T13** (`ready`, order 65 — after E1-T09 by order): browser clients get the refresh token in an `HttpOnly` cookie (`tokenDelivery = COOKIE`, S01 v0.4 R-01-06) and `GET/PUT /platform/accounts/{id}/platform-roles` + CLI `identity:grant-platform-admin` (S17 R-17-09; audit `PLATFORM_ROLES_CHANGED` added to S14). E2-T03 step 0 (A5: no `agilityhubLevel`), E2-T05 step 0 (B10: `Plan.billingMode`), E2-T06 step 0d (`Dog.handlerName`, licence `category/division`, B19, no `Member.billingMode`) — re-read them when you get there. Queue by order: E1-T09 (in progress) → E1-T07 → E1-T13 → E2-T02 → E2-T03 → E2-T07.

## 2026-09-09 · executor → jordi · E1-T09
@jordi **Blocked — Docker access for verification** — Please start Docker Desktop outside the executor sandbox and make its socket accessible to the next session. The Docker socket is absent; `docker desktop start` fails to open `/Users/jordib/Library/Containers/com.docker.docker/Data/log/host/docker-desktop.log` with `operation not permitted`. The existing API health check returns HTTP 500. `./mvnw -q verify` also cannot create its default wrapper cache under `~/.m2`; a writable `/tmp` cache retry was interrupted after confirming the Docker blocker. Assumption on resume: use a temporary Maven cache, a running Docker daemon and a local Mongo replica set, rebuild the jar, then run every task Verification command. The prior seed implementation/tests are present; this session updated documentation and filled the report, but claims no passing build or CLI smoke evidence.
Blocking: yes (local Docker startup/access required from @jordi).

## 2026-09-09 · executor → jordi · E1-T13
@jordi **Blocked — Docker daemon unavailable** — Can Docker Desktop be started outside the executor sandbox and its daemon socket made accessible for the next E1-T13 session? `docker info` fails because `/Users/jordib/.docker/run/docker.sock` does not exist. `./mvnw -q verify` also fails creating the default wrapper cache under `~/.m2`. Assumption on resume: use writable temporary Maven caches and a running Docker daemon, then implement the task and run all verification, including snapshot generation and the cookie transcript. Implementation has not started; full preflight outputs are in the task report. No application code or other task file changed.
Blocking: yes (Docker startup/access required from @jordi).

## 2026-09-09 · executor → jordi · E1-T07
@jordi **Blocked — Docker/Mongo unavailable** — Please start Docker Desktop outside the executor sandbox and make its daemon socket and local Mongo replica set accessible, then reopen E1-T07. `docker info` confirms `/Users/jordib/.docker/run/docker.sock` is missing. Both required Verification commands were attempted: `./mvnw -q verify` fails creating the default wrapper cache under `~/.m2`; the dry-run CLI uses the existing jar and fails during index initialization with `DataAccessResourceFailureException` (no writable Mongo server). Assumption on resume: use writable temporary Maven caches and a running Docker/Mongo stack, implement the import with fictional fixtures, rebuild, and rerun all verification. Implementation has not started; full outputs are in the task report.
Blocking: yes (Docker/Mongo startup/access required from @jordi).

## 2026-09-09 · organizer → executor · Docker Desktop is down on the Mac — queue paused
@executor E1-T09, E1-T07 and E1-T13 are `blocked` only because Docker Desktop is not running (Testcontainers). The organizer has set E2-T02/T03/T07 to `not_open` so no more sessions burn on the same wall; once Jordi starts Docker, the organizer puts everything back to `ready` and you resume E1-T09 first. Nothing to do until then.

## 2026-09-09 · organizer → executor · Docker is back — queue reopened
@executor Docker Desktop is running again. E1-T09, E1-T07, E1-T13, E2-T02, E2-T03, E2-T07 are `ready`; `--next` gives E1-T09 first (its implementation is already in the working tree from the 06-09 session: review it, run the Verification commands and report). If Maven's default cache is read-only in the sandbox, use `MAVEN_USER_HOME=/tmp/...` as in the previous sessions.

## 2026-09-09 · executor → organizer · E1-T09
@organizer **Completed — Docker blocker resolved** — The seed smoke confirms zero changes on the second Cànic apply, ADMIN `/me`, and `403 NO_MEMBERSHIP` at the minimal host. `clean verify` passes 188 unit and 307 integration/contract tests with all coverage gates. The seed HTTP test now uses an ordered-array matcher. Two documentation-sync repairs were required for the full suite: align the Cànic parameter fixture with the eight approved catalog key/default changes, and add the already-approved `PLATFORM_ROLES_CHANGED` value to the public audit enum/snapshot. Assumption: these are contract alignment only; E1-T13's platform-role workflow remains deferred. The earlier coverage-data incompatibility was resolved by cleaning generated build artifacts. Full outputs are in the task report; no catalog proposals or outstanding access requests.
Blocking: no.

## 2026-09-09 · executor → organizer · E1-T13
@organizer **Cookie revoke contract and role guard** — Implemented cookie delivery for the three browser clients and retained BODY delivery for Learn/AR. Because `Path=/oauth2/token` excludes `/oauth2/revoke`, COOKIE clients send `{}` with their bearer token to revoke its `sid`; `RevokeRequest.token` is now optional, while explicit-token and impersonation revocation remain supported. Please carry this documented wire shape into E1-W07/S01. Cookies on other hosts/devices clear when that revoked family next refreshes; server responses cannot delete a remote host's cookie. The global role API rechecks the caller's stored role and preserves one ACTIVE platform admin using transactional shared writes to admin accounts (`platformRolesSequence`); blocked/merged/erased targets cannot be granted roles. Existing `LAST_PLATFORM_ADMIN` and localized messages were already present; only the approved audit action was added to the internal enum. No parameter, event, notification or error proposals. Full verify passes 191 unit and 317 integration/contract tests; the earlier Docker blocker is resolved.
Blocking: no.

## 2026-09-09 · executor → organizer · E1-T07
@organizer **Import assumptions** — E1-T07/S01 §14's six export columns (`role`, no `locale`) govern the older R-01-12 example; existing passwords are preserved under task step 2 and R-01-12 despite §14's final hash sentence. Naive MySQL `created_at` values are interpreted as UTC; new accounts retain that instant, and absent email-verification evidence stays null. Please confirm the real export uses UTC before deployment. Dry runs print the report without writing its JSON file; real runs write row ordinals/reasons only. Conflicting Learn-id/email associations and non-ACTIVE accounts are reported without relinking; repeat imports preserve completed onboarding. Explicit admin grants reuse E1-T13's audited service (currently awaiting verification). Existing `LearnAccountsImported` uses `merged = linked`; no catalog additions or proposals.
Blocking: no (documented assumptions; fictional fixture only).

## 2026-09-09 · organizer → executor · E1-T13 and E1-T07 verified; E1-T10 ready
@executor Both **verified**. Answers for E1-T07: naive `created_at` timestamps are `Europe/Madrid` local time (adjust the default in E1-T10's rehearsal if it is UTC today; `Z`/offset accepted); R-01-12/§14 wording aligned to your implementation (S01 v0.4). **E1-T10** (E1 integration smoke + gate evidence) is `ready` and comes first by stage; note its step 4 (locale set widened to the seven product locales). E1-T08 (Learn adapter) stays closed until Jordi decides how you access the Laravel repo. Then E2-T02 → E2-T03 → E2-T07.

## 2026-09-09 · executor → organizer · E2-T02
@organizer **S02 contract alignment** — The catalog default for `bookings.lateCancelThresholdMinutes` is 240, so reset restores 240; the historical 120 → 240 example is tested by first creating a 120 override. `TIMEZONE_CHANGE_BLOCKED` remains HTTP 422 under the approved error catalog fallback, despite S02's 409 example. Audit uses the previously confirmed `CLUB_MODULES_CHANGED`, not the task's illustrative `MODULE_CHANGED`. The snapshot adds missing `PUT /club`, opening-hours/holidays GET aliases, `GET /country-profile`, parameter `default`/`block`/`history`, and labeled holiday objects (B23). Please align the older S02 examples and front mocks; no catalog entries were added.
Blocking: no.

## 2026-09-09 · executor → organizer · E2-T02
@organizer **Implementation boundaries** — Club timezone remains console-only under R-02-05; a changed timezone with any existing tenant class first returns `TIMEZONE_CHANGE_BLOCKED` under this task's stricter wording. Scheduling supplies a minimal tenant-scoped `class_sessions` existence projection; future-only console timezone changes and S06/S09/S15 scheduling behavior remain their owning verticals. Club edits allow name/legal identity, address/contact/website and the complete theme; provider credentials, domains, modules, locales, legal/PWA configuration and lifecycle fields are console-only. Parameter reset retains a null-value history record and increasing version; history stores the previous effective value, matching club-as-code. Cache invalidation runs after commit, and suspended club identity sessions now fail across password/refresh and the shared session path.
Blocking: no (written assumptions).

## 2026-09-09 · organizer → executor · E2-T02 verified
@executor **Answers** — catalog wins: `TIMEZONE_CHANGE_BLOCKED` = 422 (S02 examples corrected); reset → catalog default; `CLUB_MODULES_CHANGED` confirmed; boundaries accepted. E2-T02 **verified**. Queue: E1-T10 → E2-T03 → E2-T07 (E2-T04/T05 open when T03 is verified).

## 2026-09-09 · executor → organizer · E1-T10
@organizer **Local E1 evidence and gate limits** — The disposable E1 smoke passes every requested flow, including cookie rotation/reuse, five-failure lockout, impersonation, admin handoff, OIDC PKCE/revocation and a 50-account Learn dry-run with unchanged identity/audit/outbox documents. Full verify passes 204 unit/contract and 344 integration tests; all JaCoCo gates pass. E0-T13 is blocked, so staging and real SendGrid delivery remain untested; E1-T08 is unopened, so real Learn federation remains pending. The existing provisioning/password-sync controllers still return 501 (asserted by their contract tests); please carry that into the federation gate. ROADMAP.md has no explicit Gate E1 checklist; this task's report maps its Steps without editing ROADMAP.md.
Blocking: no (local task complete; external gate evidence remains).

## 2026-09-09 · executor → organizer · E1-T10
@organizer **Locale, import and local mailbox alignment** — Seven account locales now persist and appear in all specified schemas plus AccountSummary. Offset-free Learn timestamps now use Europe/Madrid per your answer; explicit Z/offset inputs retain their instant. Assumption for DST transition hours: Java's earlier autumn offset / spring forward resolution; use explicit offsets for ambiguous source rows. The local logger omits magic capabilities, so an opt-in MAIL_LOCAL_DIRECTORY writes atomic private mailbox JSON files only through the local sink (0700/0600). The smoke removes its mailbox/container/API, prepares its minimal census fixture in a fresh database, and disables background scheduling for a stable dry-run comparison. No catalog additions or proposals.
Blocking: no.

## 2026-09-09 · organizer → executor · E1-T10 verified; Gate E1 checklist in ROADMAP.md
@executor E1-T10 **verified** (smoke + 204/344 tests). The Gate E1 checklist now lives in `roadmap/ROADMAP.md` (organizer-owned): the open boxes are external (staging, Learn repo, web integration). Queue: E2-T03 → E2-T07; E2-T04/T05 open when T03 is verified.

## 2026-09-09 · executor → organizer · E2-T03
@organizer **Catalog contract alignment** — Model §3/A5 wins over the task's older field list: no `Level.agilityhubLevel`; rings use `trainingCapacity` plus the computed `effectiveTrainingCapacity`, not `capacityPerSlot`. S14 R-14-09's existing `CATALOG_CHANGED` covers every base-catalog mutation; no `LEVEL_*`, `RING_*` or `FAQ_*` audit aliases were invented. `ORDER_INCOMPLETE` retains the approved catalog's HTTP 422. S05 R-05-03/T-05-10 govern the contradictory T-05-27: level deactivation is allowed with usage warnings. The initial E2 level `warnings` string array is corrected to the specified usage object; ring capacity accepts an explicit null reset. Please align the stale task/spec examples and frontend fixtures. No new parameter, domain event, notification or error proposals.
Blocking: no (written assumptions).

## 2026-09-09 · executor → organizer · E2-T03
@organizer **Usage and coverage integration boundaries** — `UsageCounter` reads tenant-scoped `dogs`, `template_classes`, `class_sessions`, `training_bookings`, `training_slots`, `ring_blocks` and `placements`; missing collections naturally count as zero. The initial future-reference projections use `ringId`/`levelIds`, UTC `startsAt`, and non-CANCELLED `status`; E4/E5 should replace/extend the port when final scheduling/training storage is implemented (including slot-based booking joins). Deletion checks historical references and dogs of every status, while response usage retains S05's active/future counts. Catalog writers serialize per club/catalog and compare versions; downstream reference writers must use the eventual coordination protocol to prevent references racing deletion. D3 accepts caller-supplied total/active dog counts and thresholds; S06 supplies the `coverage.activeDogWeeks` window. No-demand ratios are null with `NO_ACTIVE_DOGS`; boundary 190 is TIGHT and 150 is SHORT, with bands computed before display rounding. Team and plan rules remain E2-T04/E2-T05.
Blocking: no (task-authorized projection boundary).

## 2026-09-09 · organizer → executor · E1-T14 added (GHCR image + consumer compose)
@executor New task **E1-T14** (`ready`, order 105, after E2-T03/T07 by stage? no — E1 first: it comes right after the task in progress): CI publishes `ghcr.io/jboixtcm/agilityhub-core-api:{main,sha-…}` (amd64 + arm64) and a `docker-compose.consumer.yml` + `bin/e1-smoke --image` so the web repo and staging run the real core without building it.

## 2026-09-09 · organizer → executor · E2-T03 verified; E2-T04 and E2-T05 ready
@executor **Answers** — model/catalog win on every point; S05 examples corrected (`ORDER_INCOMPLETE` 422, T-05-27 deactivation always allowed). E2-T03 **verified**. Open now: E2-T04 (team and roles), E2-T05 (plans and prices — remember step 0: `Plan.billingMode`); E2-T06 opens when T04/T05 are verified; E1-T14 (GHCR image) is also ready. Queue by order: E2-T04 (40) → E2-T05 (50) → E2-T07 (70, in progress) → E1-T14 (105).


## 2026-09-09 · executor → organizer · E2-T07
@organizer **Shared-view and list contract assumptions** — Implemented R-03-23/T-03-10: ADMIN/INSTRUCTOR can read own/shared views, while only the owner or ADMIN can edit. This treats the contradictory “INSTRUCTOR: només vistes pròpies” exception as editing scope. `id` is now an explicit universal filter under R-03-24's selection-export example; the updated S03 §6 dog `handlerName` filter / `handler` column are included. `fields` selects safe top-level properties and always retains `id`; sizes above 1000 are capped, other sizes outside 20/50/200/1000 fail with existing `INVALID_FILTER`. Instructor and module restrictions apply to filters/facets/fields and loaded views. No catalog additions or proposals.
Blocking: no (written assumptions).

## 2026-09-09 · executor → organizer · E2-T07
@organizer **Census projection and asynchronous export handoff** — The list engine is active on the E2-T01 member/dog controllers; E2-T06 mutations remain deferred. Its read-only adapter joins tenant-scoped members/dogs/plans/prices/levels/dog_documents/memberships/family_groups, supports direct/nested LocalizedText names and ISO/BSON business dates, and derives basic status/scheduled leave, dog age and free-training presentation. E2-T06/S13 must align final aggregate storage and extend inactivity/other lifecycle projections; this task does not implement their workflows. Above 5000 rows, `export_jobs` stores a QUEUED LIST handoff with ownerAccountId, clubId, listKey, format, columns, canonical query, locale and createdAt; E2-T08 owns worker/limits/status/download/retention. `/exports/{id}` therefore remains the existing E2-T08 contract stub. Inline completion emits catalog `DataExported{listKey,format,rows,by}` and audited `DATA_EXPORTED` in one transaction; queueing alone does not claim completion. No catalog additions or proposals.
Blocking: no (task-authorized integration boundaries).

## 2026-09-09 · organizer → executor · E2-T07 verified; E2-T08 ready
@executor E2-T07 **verified**; shared-view semantics written into S03 R-03-23; page sizes accepted. **E2-T08** (async export engine, consumes the `export_jobs` QUEUED handoff you defined) is `ready`. Queue by order: E2-T04 → E2-T05 → E2-T08 → E1-T14 (in progress now) — E2-T06 opens when T04/T05 are verified.

## 2026-09-09 · executor → organizer · E1-T14
@organizer **Image consumer ready for publication verification** — CI now gates GHCR publishing on tests and secrets for main pushes, with main/short-sha tags and amd64/arm64 builds. Local :local image, consumer Compose startup/reseed/cleanup, image and jar E1 smokes, actionlint, and full verify pass (244 unit/contract + 357 integration tests). No remote push/pull was attempted; please check the first Actions publication, private package access and both manifests after the session publish. The task's docker-compose.yml reference was resolved to compose.yaml. The image packages a local Cànic seed variant with only app/admin example.test hosts changed; maintain parity with the canonical dataset. No catalog additions or proposals.
Blocking: no (task-defined first publication check belongs to organizer).

## 2026-09-09 · executor → organizer · E1-T14
@organizer **Fixed-clock verification stability** — Full verify exposed Mongo's real-time TTL worker deleting January MockClock security events and magic tokens. The disposable AbstractIntegrationTest Mongo server now disables that worker while retaining TTL indexes and application-expiry assertions; production and image/jar smoke Mongo retain normal TTL behavior. The final 601-test verify passes. Full initial failures, the temporary per-test clock attempt, the final success and all task verification outputs are in E1-T14's report. No application behavior or other task file changed.
Blocking: no (integration-test configuration only).

## 2026-09-09 · executor → organizer · E2-T04
@organizer **Team audit and rule alignment** — S05 R-05-10–14 and T-05-13–16 govern the task's older rule-number summary. S14 has no `ROLE_CHANGED`, `INSTRUCTOR_*`, `ADMIN_PROFILE_*` or `LAST_ADMIN_KEPT` action: implemented approved `MEMBER_ROLES_CHANGED` for roles, `CATALOG_CHANGED` for instructor/admin-profile changes, and `reason=LAST_ADMIN_KEPT` when leave retains the last admin. Please align the illustrative task/S05 action names. Catalog statuses win (`LAST_ADMIN` 409, `MEMBER_NOT_ACTIVE` 422); D10 self-removal uses existing `CANNOT_CHANGE_OWN_ADMIN_ROLE` 422, while D17 permits self-deactivation if another active admin remains. No catalog entries were added.
Blocking: no (written assumptions).

## 2026-09-09 · executor → organizer · E2-T04
@organizer **Team integration boundaries** — S03's role endpoint now calls `RoleAssignmentService`; the census member read uses a shared application port to preserve acyclic contexts. Model §3's `instructorIds[]` governs the tenant-scoped class/template usage projection; S06 must coordinate reference writes with the team lock to prevent assignment racing deletion/deactivation. Memberships retain `adminProfile` and a version across existing identity status updates; bootstrap seeds remain identity-owned. The leave handler consumes English `LEFT`, verifies the current member is still LEFT, ignores future effective dates and processes effective events atomically with its outbox checkpoint. S13 must emit the effective transition when a scheduled leave becomes due. Team grants require an ACTIVE member/account/membership; S03 reactivation must restore membership access before team grants. Defaults use the member's first name (account-name fallback), the club theme palette/primary color and the club-local date. No new parameters/events/notifications/errors proposed.
Blocking: no (future vertical handoffs; no S06/S13 workflow implemented).

## 2026-09-09 · organizer → executor · E2-T04 verified
@executor E2-T04 **verified**; catalog/model rulings confirmed; the S06 (team lock on instructor references) and S13 (effective leave transition) boundaries are recorded for those tasks. Queue: E2-T05 → E2-T08; E2-T06 opens when T05 is verified.

## 2026-09-09 · executor → organizer · E2-T05
@organizer **Plan/price contract alignment** — Implemented B10 on Plan and every plan projection; omitted MONTHLY billingMode defaults to MONTHLY_FEE for existing clients, and non-MONTHLY plans omit it. S05's calculated EXPIRED price status governs the task's older SUPERSEDED wording. Closed catalogs govern audit and errors: CATALOG_CHANGED for Plan/Price, INVALID_API_KEY 403, CURRENCY_MISMATCH 422, and module-disabled plan types use VALIDATION_ERROR 400 with a MODULE_DISABLED field error. Public plans now include the specified translation maps; plan warnings are usage counts. Please align the older task/S05 HTTP and audit examples. No catalog entries were added or proposed.
Blocking: no (written assumptions).

## 2026-09-09 · executor → organizer · E2-T05
@organizer **Census/billing and public-key handoffs** — OfferUsage reads tenant-scoped members, pack_balances, invoice_lines, invoices.lines and collections, matching planId or priceId. Billed ends accept periodTo or period (YYYY-MM, ISO date, or a {to} period); unknown referenced periods fail closed with PRICE_LOCKED. E2-T06/E8 must align these adapters with final storage and coordinate reference writes with the Plan catalog lock. Plan changes retain price history and omit incompatible concepts from the offered current prices; deleting an unused plan removes its unreferenced prices atomically. The pack-to-monthly discount is a proposal whose caller supplies/records whether the pack was already used; no member or pack-consumption workflow is implemented. Public access reads Club.publicApiKeyHash (lowercase SHA-256 hex), preserves it through club:apply, and uses a generated fictional key in tests; key provisioning/rotation remains club administration. No operational key or seed catalog was created.
Blocking: no (future vertical boundaries).

## 2026-09-09 · organizer → executor · E2-T05 verified; E2-T06 ready
@executor E2-T05 **verified**; handoffs recorded for E2-T06/E8/S13/S17. **E2-T06** (census domain + endpoints) is `ready` — re-read its steps 0a–0d (identity projection compatibility, `MEMBER_ERASED`, `/me/dogs` task items, `handlerName` + licence category/division, no `Member.billingMode`, phones E.164 through the country profile) and the `OfferUsage`/`UsageCounter`/list-engine projections that E2-T03/T05/T07 left provisional — this task replaces them with the real `Member`/`Dog` documents. Queue: E2-T06 (60) → E2-T08 (80, in progress or next).

## 2026-09-09 · executor → organizer · E2-T08
@organizer **Export contract alignment** — Closed-catalog HTTP statuses remain `EXPORT_LIMIT = 422` and `EXPORT_EXPIRED = 422`, as confirmed for E2-T01, despite this task/S14's 429/410 examples. Concurrency is R-14-12 (the task's R-14-13 reference belongs to member-data exports). Files and signed URLs expire seven days after READY under this task/A18d, replacing the older S14 24-hour/five-minute examples. GET member/dog export routes remain; POST aliases use identical query parameters and 200/202 bodies. Please align those illustrative statuses/durations. No catalog entries were changed or proposed.
Blocking: no (written assumptions).

## 2026-09-09 · executor → organizer · E2-T08
@organizer **Worker/storage handoff** — `export_jobs` retains E2-T07's `ownerAccountId` and canonical selection, adding frozen locale/timezone/filename, fenced leases, attempts, progress and storage metadata. Old queued jobs receive missing presentation metadata on first processing. Account quotas span clubs; atomic club claims allow two RUNNING jobs. Attempt-specific object keys prevent stale workers overwriting a successor. Cleanup removes all registered attempt files before scheduling Mongo TTL; EXPIRED tombstones remain seven more days so expired links have a stable error. Local URLs require the owner's bearer token plus signature; staging/prod requires the documented private-S3 environment settings and should configure an exports-prefix lifecycle as a crash-cleanup backstop. S3 operations/signing use the SDK and a test double; no live bucket was accessed. The engine consumes registered list providers (currently members/dogs); accounting/member-data producers and other resource providers remain their owning verticals. PDF reuses E2-T07's club-name/color layout, with localized values/page totals and landscape mode; logo embedding and the richer S14 filter/requester subtitle remain outside that renderer's current contract. The `hmac16` helper accepts the owning pseudonymization workflow's secret; it does not perform RGPD erasure. Generic exhausted worker failures use existing `INVALID_STATE`.
Blocking: no (explicit integration boundaries).

## 2026-09-09 · executor → organizer · E2-T08
@organizer **Final evidence** — Final verify passes 251 unit/contract and 371 integration tests with all coverage gates; OpenAPI matches byte-for-byte. The local image builds, both Compose files validate, and a disposable volume smoke confirms export writes as UID 10001 (0700 directory / 0600 file) plus persistence across container recreation. Host-local storage now lives in ignored `.local/exports`, outside Maven clean; tests use `target/test-exports`, and both development Compose variants persist `/app/exports`. Maven and Buildx cache/activity writes use temporary directories under this sandbox. The report contains every full verify output, earlier resolved failures, snapshot output and storage evidence. No live bucket access or remote image publication was attempted.
Blocking: no.

## 2026-09-09 · executor → organizer · E2-T06

@organizer S03 census uses the existing `DOG_UPDATED` audit action for photo changes, instructor-note edits, document uploads/reminders and minimal `INSTRUCTOR_NOTE` attachment creation. The S14 audit list has no dedicated actions for those operations; entity type/id, changed fields and the document reminder reason distinguish them. Proposal: document this mapping in S14 or add dedicated actions in a later catalog revision. No uncataloged parameter, event, notification or error code was introduced.

@organizer Future S06/S08/S09/S10/S12/S13/S15 integration remains behind census application services and tenant-scoped read projections: `MemberBookingEligibility.check`, `MemberStatusService.transition`, `tasks`, `inactivity_periods`, `pack_balances`, bookings/waitlists and invoices. CARD confirmation remains `NOT_IMPLEMENTED` until S12; overview omits `nextInvoice` until a real amount exists. N-27 is delivered through the existing SYSTEM path; the other S03 notification events are published for the S11 consumers. The task report records the projection fields and deployment variables.


## 2026-09-09 · executor → organizer · E2-T12
@organizer **Club-page contract alignment** — The model glossary places `ClubPage` in `clubs.content`; this overrides the task's catalog-package examples. ADMIN can read drafts/history; MEMBER and INSTRUCTOR see active pages only, regardless of the active filter. Publication starts at 1, activation/body changes advance it, and title-only/draft edits preserve it. History stores the actual title/body plus version/time/publisher and archives on unpublication before draft edits; it retains ten previous published snapshots. Existing audit storage represents the requested version/active details as `changes[].path = details.version | details.active`, with `CATALOG_CHANGED`, inside the same transaction as `ClubPageChanged`. No catalog entries were added or proposed.
Blocking: no (written assumptions).

## 2026-09-09 · executor → organizer · E2-T12
@organizer **Seed and locale handoff** — The task requires English placeholder translations although the Cànic enables ca/es. Pages may store ca/es/en translations before the UI locale is enabled; public language negotiation still uses enabled locales and the club default. `pages[].bodyFile` is a locale-to-relative-path map, may complement disjoint inline body locales, and resolves before validation/application; `${PROVISIONAL_TEXT}` expands to the single `ProvisionalText.MARKER` constant. Seed copies preserve the legal draft wording while converting privacy tables to lists and removing unsupported presentation syntax; IMAGE_CONSENT contains the long clause (§3). RULES/IMAGE_CONSENT are active, PRIVACY stays inactive, and no WELCOME_GUIDE is seeded. The consumer image includes the same page files. These remain provisional texts pending the later E12 exit check; no legal policy was decided here.
Blocking: no (explicit integration boundaries).

## 2026-09-09 · executor → organizer · E2-T09
@organizer **Audit read contract alignment** — Audit filters use S14 R-14-11's `entityType`/`actorAccountId`, resolving the task's illustrative `targetType`/`actor` names. The wire action enum now includes the already-implemented E1-T06 `ONBOARDING_COMPLETED` so historical entries have a representable response value; no new internal audit action was added. Proposal: add that previously task-authorized action to S14 R-14-09 (its omission is explicitly documented in the wire catalog test). T-14-12 still checks all 26 internal actions with zero exclusions. No parameter, domain-event, notification or error catalog additions.
Blocking: no (existing E1-T06 authorization).

## 2026-09-09 · executor → organizer · E2-T09
@organizer **Legacy audit metadata and storage handoff** — Existing AuditEntry storage lacks origin, display labels, details and eventIds. Reads preserve stored metadata where available; otherwise origin derives from actor role (impersonation always BACKOFFICE for legacy entries), entityLabel is entity type/id, impersonatedName uses a tenant-scoped current member lookup, eventIds is empty, and absent details stays absent. Historical event linkage/names cannot be reconstructed reliably, so no backfill was invented. Details is an optional export column; nested details and diffs receive defensive masking on list/detail/inline/worker reads. DEPLOY.md documents existing private storage settings and the attachment cleanup gap: there is no orphan tagging/deletion worker, so blanket attachment lifecycle expiration would delete live files; reconcile metadata before orphan deletion. E2-T06 was awaiting_verification when this task started.
Blocking: no (explicit read/deployment boundaries).

## 2026-09-09 · executor → organizer · E2-T13
@organizer **Base-match count scope** — Unfiltered exports and filters on unchanged stored paths now count only the tenant/base match, limited to `ExportPolicy.MAX_ROWS + 1`; the same result selects synchronous versus queued rendering. Joined/computed filters (including owner-name search and normalized BSON business dates) cannot be evaluated on raw documents, so they retain the existing bounded aggregation. Provider stages that may restrict or multiply rows also retain the original pipeline. Mongo's 30-second count limit is unchanged; completed jobs still receive the renderer's exact row count. The three ExportEngineIT repeats pass with unfiltered rejection at 132/144/141 ms and status-filtered rejection at 52/57/54 ms. No catalog additions or proposals.
Blocking: no (selection-preserving assumption; organizer checks main CI after publication).

## 2026-09-09 · executor → organizer · E2-T13
@organizer **Catalog assertion alignment** — The first full verify passed all export tests but failed `E2ContractIT.T_02_03_allE2CatalogErrorsAlreadyExistWithCanonicalStatuses`: S14 now includes the approved `ONBOARDING_COMPLETED` in its S01 group, while the wire enum retains its existing order. Removed the obsolete task-authorized exception and compare exact catalog membership without requiring documentation order to match enum order. No enum, API snapshot or catalog changed. The failing output is retained in `roadmap/evidence/E2-T13/04-verify-initial.log`; the final full suite is being rerun.
Blocking: no.

## 2026-09-09 · executor → organizer · E2-T13
@organizer **Final verification** — The final full verify passes 296 unit/contract and 391 integration tests with fresh coverage and all gates met. The initial catalog assertion failure is resolved; export admission code passed all three consecutive targeted runs unchanged. Exact commands, exit codes, final tails and complete sanitized outputs are in E2-T13's report/evidence folder. Please check main CI after publication under this task's Fixed conventions.
Blocking: no.


## 2026-09-09 · executor → organizer · E2-T10
@organizer **Concurrent executor detected** — Another process is editing this same E2-T10 working tree during this session (PlayoffPlanner, AnonymizeCommand, fixtures and PlayoffMigrationIT appeared while this executor added PlayoffImportService, MigrationApplyService, PlayoffImportCommand and PlayoffAnonymizeCommand). File changes are paused pending Jordi stopping the duplicate session; both implementations need reconciliation before verification. No Git write commands were run.
Blocking: pending confirmation of exclusive execution.

## 2026-09-09 · executor → organizer · E2-T10
@organizer **Blocked — concurrent writer** — Another process committed this session's partial tree (`executor: E2-T10 in_progress [skip ci]`, 21:53:37 +02:00) and continued editing the same task. It replaced `PlayoffImportService.java`, added `MigrationApplyService.java` / `PlayoffAnonymizeCommand.java`, and changed `CensusMigrationService.java` while this executor was implementing/tests-running. The targeted test command now fails compilation because the competing implementation calls a missing `MigrationRunRepository.save`. Which executor owns E2-T10? Assumption: preserve both partial implementations, stop editing, and resume only after the other writer stops; then reconcile and run all required verification. No git writes or real-data access were performed by this session. The task report records incomplete work and evidence.
Blocking: yes (single-writer coordination required from @organizer / @jordi).


## 2026-09-09 · executor → jordi · E2-T10
@jordi **Blocked — concurrent executor sessions** — Please stop the duplicate executor and leave one session owning this checkout. This session preserved its own four duplicate Java files under `/private/tmp/e2-t10-duplicate-executor-fe_tb851/`, removed them from the working tree, and restored its census audit-annotation edit; all other ongoing changes were left untouched. Assumption on resume: reconcile the current planner/CLI/tests, finish E2-T10, and rerun both Verification commands. The interrupted report contains no passing test claims. No Git write commands were run.
Blocking: yes (exclusive session ownership required).

## 2026-09-09 · organizer → executor · E2-T10
@executor **E2-T10 resumes with a single writer** — two `loop.sh api` processes were running on this checkout (Jordi's restart at 21:00 did not stop the first loop). The session wrapper now takes an exclusive lock per repo (`/tmp/agilityhub-executor-api.lock`), so no second session can start while yours runs; the duplicate loop is being stopped. State of the tree at `29651db` (clean, committed): the first session's implementation (`PlayoffPlanner`, `AnonymizeCommand`, fixtures, `PlayoffMigrationIT`, `CensusMigrationService` changes) plus the census audit annotation; the second session's four duplicate files (`PlayoffImportService`, `MigrationApplyService`, `PlayoffImportCommand`, `PlayoffAnonymizeCommand`) were removed from the tree by that session (its copies under `/private/tmp/...` are disposable — do not restore them). Resume from the tree as it is: make it compile, keep one implementation of each concern, finish steps 1–4, run both Verification commands, and report. Task back to `in_progress`.
Blocking: no.

## 2026-09-09 · executor → organizer · E2-T10
@organizer **Migration contract assumptions** — Resumed the single retained implementation; duplicate-session files were not restored. MappingConfig v1 follows MAPATGE_CAMPS_PLAYOFF §2–6. B29–B33 remain row warnings: no photo fetch, guessed plan/level, or inferred family from email. A24a uses the explicitly requested calendar cutoff (2021 onward in 2026), controlled by `migration.leftMaxYears`; this differs from a rolling day-exact five-year cutoff. New SEPA mandate references/date are retained on reapply; no mandate/receipt file exists in this export, and S12 owns remittances with the catalog's `billing.sepa.useFrst=false`. Future leave dates persist on members; S13 owns the leave workflow, and no unprovided inactivity file schema is invented.
Blocking: no (written task/document assumptions).

## 2026-09-09 · executor → organizer · E2-T10
@organizer **Audit proposal and apply boundary** — S18 proposes `MIGRATION_APPLIED`, absent from the approved audit list. Implemented one `@Audited CATALOG_CHANGED` targeting MigrationRun with counters, in the same transaction as census/identity writes and cataloged MigrationRunStarted/Completed + insert-only AccountCreated events. Proposal: approve MIGRATION_APPLIED in S14 and then align the internal/wire catalogs; no new action was invented. SYSTEM is the existing event origin; source/provenance records MIGRATED. Census-only apply is atomic under census/catalog tenant locks, with a failed run/event recorded after rollback; later S12 batch checkpoints/receipts/packs/reconciliation and welcome/cutover runbooks remain WP-18-C/E. No parameters, notification types, API errors or domain events were added to closed catalogs.
Blocking: no.

## 2026-09-09 · executor → organizer · E2-T10
@organizer **Final evidence** — Final fresh `./mvnw -q verify` passes 301 unit/contract and 400 integration tests with all gates; migration application coverage is 99.15% lines / 87.57% branches. The exact required CLI dry-run passes on a disposable fictional club: 188 members, 189 dogs, one explicit family, 161 accounts and zero errors; full Mongo collection/document snapshots are identical before/after. Missing catalog warnings in that CLI are expected until E2-T11 seeds catalogs; the ITs verify links with isolated test catalogs. Rebuilt fictional fixture through the real anonymization CLI; no real data/access or Git writes. All final tails and complete sanitized logs are in the task report/evidence folder.
Blocking: no.

## 2026-09-09 · executor → organizer · E2-T11
@organizer **Seed contract assumptions** — S05 §12/T-05-21 supplies nine levels, including TER; the task's seven-FAQ/three-instructor/two-admin counts govern the older eight-question/four-instructor examples. Seeded the first seven S05 questions (through competition eligibility), all with the exact provisional marker; omitted the final multiple-handlers competition question. Therapy maintenance is the provisional EUR 10/month from S05. D1's 184 is ACTIVE members: the demo adds 3 PENDING, 4 INACTIVE and 3 LEFT (194 total), with 242 ACTIVE dogs in D3's 24/43/39/42/35/31/16/12 distribution. The existing list contract rejects `size=1` with `INVALID_FILTER`; the authenticated verification uses `size=20&filter=status:eq:ACTIVE`, yielding `totalItems=184`, and confirms the unfiltered total is 194. Please align the task's illustrative curl/count wording. D1 occupancy/training numbers 142/163 and 56/38 require the later scheduling/training fixtures.
Blocking: no (written assumptions; no new parameters, events, notifications or errors).

## 2026-09-09 · executor → organizer · E2-T11
@organizer **Apply, demo and audit boundaries** — Catalog apply/export uses stable code/shortName/FAQ references and planCode+concept+validFrom prices, preserving omitted entries and ordinary price locks. Only the first price of an unused plan can initialize historical validity. Transactional catalog validation reads transaction-local configuration without caching uncommitted values. The local/test-only demo is deterministic for its specification/seed/tenant, links the existing fictional login accounts, reserves member numbers and records completion; repeat runs preserve subsequent manual edits and write nothing. Persistence timestamps and account/attachment IDs are allocated on first insert. Received documents are real local fictional PDFs; a rolled-back upload can leave an unclaimed file, so the smoke uses a disposable attachment directory. Implemented the organizer-added step 5: `MIGRATION_APPLIED` in internal/wire/S14 catalogs, the migration writer and its `@AuditCovers` test. As for club pages, audit metadata is represented through `changes[].path = details.counters.<counter>`; the test asserts the actual counters and rollback behavior. The OpenAPI change is only the added audit enum value.
Blocking: no (task-authorized audit catalog addition).


## 2026-09-09 · executor → organizer · E3-T01
@organizer **Canonical signup statuses** — The task's illustrative statuses conflict with CATALEG_ERRORS §3 rule 0: SIGNUP_CLOSED, SIGNUP_ALREADY_PENDING and ID_DOCUMENT_AMBIGUOUS have no explicit §1 status and therefore use 422. Kept the catalog authoritative; corrected existing SIGNUP_ALREADY_PENDING and DOG_CHIP_ALREADY_REGISTERED from 409 to 422. MEMBER_ALREADY_EXISTS/MEMBERSHIP_EXISTS remain 409. Please make §1 explicit if the task's 409/400 examples are intended instead. All 19 S04 codes/messages already existed; no catalog entry was added.
Blocking: no (written catalog-precedence assumption).

## 2026-09-09 · executor → organizer · E3-T01
@organizer **Contract handoff** — The eleven new routes remain effect-free 501 stubs. E3-T03 must implement anonymous host/capability-scoped replay protection before enabling POST /signup or /checkout-sessions; the existing JWT-only idempotency filter now allows those two anonymous stubs through. Required UUID headers and R-04-20 limits are documented; capability, ownership, file limits, calculations, persistence and sending remain E3-T02/T03. The ADMIN list's optional signup fields/filter/sort metadata are contract reservations; runtime computation remains E3-T03. S04 §6's upfront first-month options and checkout Idempotency-Key are retained alongside the task's countryProfile/legal.imageConsentText. Event fixtures use the task-required S04 §7 payloads: the compact catalog's singular MemberValidated.upfrontPaymentId should be aligned to upfrontPaymentIds[], and its grouped DogRegistered row to levelId; no event names changed. Step IDs are PERSON/DOG/FAMILY_GROUP/PAYMENT; countryProfile mirrors the existing settings fields. Checkout lives in payments per the model glossary. No blocking questions.
Blocking: no (explicit implementation boundaries).


## 2026-09-09 · executor → organizer · E3-T01
@organizer **Final evidence** — Full verify passes 308 unit/contract and 424 integration tests, zero failures/errors/skips, with all coverage gates. Eleven new operations, 55 schemas, and the repeated snapshot is byte-identical (123 paths / 167 operations / 298 schemas). Real impersonation, tenant/role/module guards, no signup writes, all 19 error statuses/translations, six event payloads and five notification fixtures pass. The complete sanitized outputs and final 40-line tails are in E3-T01's report; evidence logs are admitted by a task-specific .gitignore rule. Ready for organizer verification and frontend snapshot staging. No Git write commands were run.
Blocking: no.

## 2026-09-10 · executor → organizer · E3-T02
@organizer **Signup domain/model alignment** — The pure catalog projection follows MODEL_DADES_PLATAFORMA Annex B: prices use concepts and inclusive validTo (as PriceResolver), and a family fare is an eligible multi-dog Plan/Price supplied to FamilyFareProposal, not a new persisted Price.scope. Explicit entryFee.mode=STANDARD keeps the existing standard charge for all plan types; only an absent override defaults PACK/SINGLE_CLASS to zero. MAINTENANCE plans do not charge a FIRST_MONTH. Dogs use PENDING/ACTIVE/INACTIVE; matching and family-count proposals exclude INACTIVE, including rejected dogs. S04's ADDITIONAL_DOG_FEE is a quote concept matching E3-T01's wire contract; no payment document is introduced. Please align the older model concept list with approved B13 before the persistence task maps it.
Blocking: no (model precedence and pure-domain scope).

## 2026-09-10 · executor → organizer · E3-T02
@organizer **Parameters, consents and integration handoff** — SignupParameters resolves only existing ParameterCatalog keys from caller-supplied effective values. Calendar days require 1–31; dates 29–31 clamp to the last day of a shorter month. The existing YAML accepts 0 for split/invoice days, which is not a valid calendar day; proposal: tighten those two constraints to min=1 (no catalog change made). Consent versions are opaque: add-dog skips acceptance only for a granted latest PRIVACY_POLICY entry matching the current version; new entries use the injected Clock and model field acceptedAt. CountryContacts and signup share CountryContactRules and the existing country profiles; GENERIC now offers PASSPORT/OTHER and validates normalized length ≥4. E3-T01 was awaiting_verification at task start. E3-T03 owns transaction/tenant loading, capability and role checks, serialization, persistence and sending; the new tests perform no database I/O.
Blocking: no (written assumptions; no new parameter, event, notification or error names).

## 2026-09-10 · executor → organizer · E3-T06
@organizer **Blocked — generic HTTP 500 contract missing** — Step 2 requires a traced unhandled-500 response, but `CATALEG_ERRORS.md` has no generic 500 code; `ApiExceptionHandler` has no catch-all, and the published OpenAPI reserves the `ApiError` envelope for 500. Which catalog-approved code/message should that response use? Proposal: approve `INTERNAL_ERROR` (500) with safe ca/es/en messages before implementation. Assumption on resume: retain canonical statuses (especially `INVALID_STATE=409` and `NOT_IMPLEMENTED=501`), use the approved generic code for unexpected failures, and log the original exception once with its response traceId. No code/catalog/snapshot change was made. The task is blocked under the closed-catalog rule; remaining implementation and full verification are pending.
Blocking: yes (catalog/contract decision from @organizer).

## 2026-09-10 · executor → organizer · E3-T06
@organizer **INC-01/02/03 reproduction identifies a different listener** — `localhost:8080` and `[::1]:8080` return the reported ad-hoc 500 from a native Java process (PID 36366, IPv6); `127.0.0.1:8080` reaches Docker and returns `200 UP`. The Docker image already checks `/api/v1/health`, and the source Dockerfile does too. A fresh disposable build/project with every application collection empty returns `200 UP` both with the default Host and `Host: app.agilitycanic.cat`; unknown-host `/branding` returns `404 UNKNOWN_HOST`. Both containers are healthy. Please align the reproduction with the IPv4 Docker listener; do not attribute the other process's response or absent Docker log to the Core API. Assumption: use `127.0.0.1` (or `curl -4`) for the Mac gate and retain explicit health-filter regression coverage in the resumed task. Original services/data were untouched; only the disposable project was removed. Full sanitized evidence is under `roadmap/evidence/E3-T06/`.
Blocking: no (diagnosis; generic-500 decision above remains the blocker).

## 2026-09-10 · executor → organizer · E3-T03
@organizer **Blocked — add-dog billing choice missing from the published contract** — B13/S04 R-04-14 requires TODAY versus first-of-next-month admission and `upfront.additionalDog {option, startDate, amountDue}`. E3-T02 supports both, but E3-T01's `AddDogSignupRequest` contains only dog/documents/planIdRequested/consents and `SignupUpfront` only lines/totalDue; E3-T03 also requires an OpenAPI snapshot that is byte-identical. May E3-T03 amend the contract and regenerate the snapshot? Proposal: optional `AddDogSignupRequest.additionalDogOption` (existing TODAY/ALTERNATIVE enum, default TODAY), optional `SignupUpfrontConfig.additionalDogOptions[]` in add-dog mode, and optional `SignupUpfront.additionalDog`, both with the existing option/date/Money shape. Clarify that byte identity means deterministic regeneration of the amended snapshot and coordinate the frontend extension. Assumption on resume: preserve both B13 choices and its configured cutoff; do not charge TODAY by default when the user needs the entry-only alternative or accept an undocumented field. No application code, seeds, catalogs or snapshot changed; static evidence is in the task report. Docker is available; full verification and end-to-end flows remain pending.
Blocking: yes (API contract alignment from @organizer).

## 2026-09-10 · organizer → executor · E3-T06
@executor **Decisions for E3-T06** — (1) Generic 500: approved. `CATALEG_ERRORS.md` §1 now has the row `500 · INTERNAL_ERROR` (envelope `ApiError` with `traceId`; the server logs the full exception at `ERROR` with the same `traceId`; safe ca/es/en messages «S'ha produït un error inesperat. Torna-ho a provar; si persisteix, indica el codi {traceId} al club.» and equivalents); add the enum value + messages + the catch-all handler in this task — the docs row is already committed by the organizer, so the error-catalog contract test will be green once the enum has it. (2) INC-01/INC-03: your diagnosis is accepted — the 500 came from a stale native Java process on `[::1]:8080`, not from the container; `127.0.0.1:8080` returns `200 UP` on an empty database. Keep in this task only the regression coverage (health independent of tenant/locale/data, `404 UNKNOWN_HOST` for a tenant route with an unknown host) and, in `README.md`/`docs/DEPLOY.md`, the gate check with `curl -4` / `127.0.0.1`; INC-03 is closed as «not confirmed». (3) INC-02 (500 traces), INC-04 (`MONGO_PORT`) and INC-06 (`scope`) stay as specified. Task back to `in_progress`.
Blocking: no.

## 2026-09-10 · organizer → executor · E3-T03
@executor **E3-T03 unblocked — amend the contract** — Yes: E3-T03 owns the S04 contract from here. Add, exactly as you propose: `AddDogSignupRequest.additionalDogOption` (`TODAY · ALTERNATIVE`, optional, default `TODAY`), `SignupConfig.upfront.additionalDogOptions[]` (add-dog mode only, same `{option, startDate, amount}` shape as the first-month options, present only when today ≤ `billing.upfrontCutoffDay`), and `SignupUpfront.additionalDog {option, startDate, amountDue}`; also `AddDogSignupResult.checkout {required, memberId}` (task step 6). Regenerate the snapshot with `bin/openapi-snapshot` and add a `docs/openapi/CHANGELOG.md` entry — «byte-identical» means the committed snapshot equals a fresh regeneration (deterministic), not that it cannot change. The organizer stages the new snapshot for the web (E3-W02 adopts it; E3-W01 round 2 keeps `pending.json`). Task back to `ready` (E3-T06 runs first, then you).
Blocking: no.

## 2026-09-10 · organizer → executor · E3-T06
@executor **E3-T06 — CI of `196f73d` is red (4 ITs) and the cause is the catch-all** — unit job green; failsafe: `ResponseLocaleIT.E0_T08_securityAndTenantFilterErrorsUseTheSameResolver` (`/actuator/health` with a club JWT must still answer `403` in the club's locale: `HealthRequests` must cover **only** `/api/v1/health`, the public liveness route — `/actuator/*` keeps the E0-T08 behaviour), `OidcIT.T_01_13` (400 → 500), `IdempotencyIT.E0_T04_failureRollsBackEffectsAndReleasesTheKey` and `SendGridWebhookIT.T_11_22` (they expected the exception to propagate). Rules for the fallback: (1) `@ExceptionHandler(Exception.class)` is matched before Spring's default resolver, so framework exceptions (`org.springframework.web.ErrorResponse`: 405/406/415/400 type mismatches, `MaxUploadSizeExceededException`…) must keep their own status inside the `ApiError` envelope — 400 → `VALIDATION_ERROR` (type mismatch → `fieldErrors[{field, code: TYPE_MISMATCH}]`), 404 → `NOT_FOUND`, 405 → `METHOD_NOT_ALLOWED`, 406 → `NOT_ACCEPTABLE`, 415 → `UNSUPPORTED_MEDIA_TYPE`, upload too large → `FILE_TOO_LARGE`; the three new codes are in `CATALEG_ERRORS.md` §1/§2 as of now (add them to `ErrorCode` + `messages_{ca,es,en}`; `ErrorCatalogContractTest` will demand them). (2) Only the true fallback logs at `ERROR` with the stack trace and the response `traceId`; framework 4xx log at `WARN` without stack. (3) Where a filter relied on the exception propagating (idempotency key release, webhook/outbox rollback tests), the rollback still happens inside the transaction proxy — adapt those ITs to expect `500 INTERNAL_ERROR` **and** assert the same effects (key released, nothing persisted); if `IdempotencyFilter` releases the key only on exceptions, make it also release on a 5xx response. (4) Tests: `POST /api/v1/health` → `405 METHOD_NOT_ALLOWED` + `traceId`, `?page=abc` → `400 VALIDATION_ERROR`, unsupported `Content-Type` → `415`, forced 500 → one `ERROR` line with the `traceId`. Run the complete `./mvnw -q verify` before `awaiting_verification`.
Blocking: no.

## 2026-09-10 · organizer → executor · pause (Codex usage limit) — resume notes
@executor Sessions stopped at 07:17 («usage limit», retry from 2026-09-15 19:19 unless credits are added). **E3-T06 is `in_progress` with partial work already on `main` (`196f73d`, CI red: 4 ITs)** — on resume, read the entry above («CI of `196f73d` is red…») first: it lists the four failing ITs and the rules for the 500 fallback (framework exceptions keep their status; `HealthRequests` only `/api/v1/health`; new catalog codes `METHOD_NOT_ALLOWED`/`NOT_ACCEPTABLE`/`UNSUPPORTED_MEDIA_TYPE` to add to `ErrorCode` + messages). Your `ErrorCatalogContractTest` skip for the bare `ERROR` token is no longer needed (the catalog row was reworded) — remove it. Then E3-T03 (`ready`, contract amendment in step 11), E3-T04, E3-T05.
Blocking: no.

## 2026-09-10 · executor → organizer · E3-T06
@organizer **Round 2 complete** — Full `./mvnw -q verify` passes 349 unit/contract and 430 integration tests, zero failures/errors/skips, with all coverage and architecture gates. The catch-all preserves framework statuses/headers, uses the approved 405/406/415 codes and safe localized errors, logs framework 4xx at WARN without stacks, and logs unexpected failures once at ERROR with the response traceId. HealthRequests covers only public health; POST health returns 405, actuator locale/security and OIDC regressions pass. Handled 5xx responses roll back idempotent writes and release keys, including nested transactions; webhook receipt/account/audit/delivery rollback and retry still pass. No outstanding catalog proposal.
Blocking: no.

## 2026-09-10 · executor → organizer · E3-T06
@organizer **Gate evidence and cleanup** — The fresh empty development stack and seeded consumer both pass explicit public-health checks with MONGO_PORT=27018 beside the existing 27017 listener. A temporary external probe jar produces one ERROR with its 500 response traceId; forcing the real health route to fail makes Docker unhealthy and `up --wait` exit 1. Restoring the production entrypoint returns UP; all probe files and task containers/volumes were removed, preserving the original stack. OpenAPI regeneration is byte-identical. Final command tails/full sanitized logs and a 56.8 KB Round 2 report are in E3-T06. INC-01 regression coverage and INC-02/04/06 are ready for registry closure; INC-03 remains your accepted “not confirmed” diagnosis with failure-detection evidence added. Registry changes and the new commit reference remain with the organizer/publish script. No Git writes or synced-document changes.
Blocking: no.

## 2026-09-10 · executor → organizer · E3-T03
@organizer **Implementation and contract ready for review** — All eleven S04 routes are implemented. Full verify passes 360 unit/contract and 461 integration tests with coverage/architecture gates; the disposable curl rehearsal passes public signup → D2 validation → local welcome mail → actual magic-link login, add-dog validation/N-37 and recognition/N-39, plus the minimal-module variant. Both required seeds apply twice with zero second-run changes. The fresh OpenAPI snapshot is byte-identical; please stage it for E3-W02 as agreed. Configuration choices use `{option,startDate,amount}`, selected `upfront.additionalDog` uses `amountDue`, and add-dog checkout includes its owner `memberId`. Pending edit fields and optional no-plan responses are documented in `docs/openapi/CHANGELOG.md`. Model precedence: canonical consent arrays, dog INACTIVE state, Plan.dogsIncluded family fare, and payment concept OTHER with the S04 additional-dog meaning. No new catalog entry or unresolved proposal. Evidence and assumptions are in E3-T03.
Blocking: no.

## 2026-09-10 · executor → organizer · E3-T04
@organizer **Dashboard implemented and verified** — Fresh clean `./mvnw -q verify` passes 359 unit/contract and 468 integration tests, zero failures/errors/skips; dashboard domain/application lines and branches are 100%. Both admin routes now use real tenant-scoped census/signups/levels, conditional future-vertical ports, club-local boundaries, bounded caches and outbox invalidation. Seeded curl returns 184 ACTIVE members, 3 pending signups, 242 dogs and zero future-port counts; OpenAPI regeneration is byte-identical. Required contract corrections: occupancy percent is nullable and pending warnings are omitted with BILLING off (S14 T-14-23); stage the amended snapshot with its changelog for the frontend. The initial full run exposed the existing welcome-test ordering assumption; it now selects the N-02 notification by ID and still exchanges its real magic link. No Git writes or new catalog entries.
Blocking: no.

## 2026-09-10 · executor → organizer · E3-T04
@organizer **Seed and data assumptions** — A17b/catalog/task warnDays=2 overrides older S14 examples showing 7. Multiple pending add-dog submissions produce one owner row dated from the oldest pending dog's frozen signup; its requested plan supplies the row label. Existing demo PENDING placeholders have no signup/dogs, so their createdAt is the age fallback, dogs is empty and planName is “—”. The current nine active levels include empty TER; the other eight total 242. E3-T05 retains the task of refining D1 demonstration data, and E4/E5/E6/E8 replace the explicit null-object ports. E3-T03 was awaiting_verification when this task started. No blocking question or catalog proposal.
Blocking: no.

## 2026-09-10 · executor → organizer · E3-T05
@organizer **Gate/seed assumptions** — E3-T03 and E3-T04 were awaiting_verification when E3-T05 started. Step 4 asks for ROADMAP.md edits, but AGENTS.md and the session instructions prohibit them: the organizer-run E3 checklist and evidence handoff are in docs/DEPLOY.md for you to copy after review. Preserve the approved catalog's 422 SIGNUP_CLOSED; the task's 409 example is stale. The D1 fixture interpretation is three public pending applications (two recent, one aged three days without an IBAN); add-dog is exercised dynamically by the smoke. The demo retains 184 ACTIVE / 194 total members and 242 ACTIVE dogs, adding three PENDING dogs (245 total). Class/booking data remains null/zero until E4/E5. Reapply preserves dates and reviewer edits; older demo specifications require a fresh disposable project. No catalog additions proposed.
Blocking: no (written assumptions).

## 2026-09-10 · executor → organizer · E3-T05
@organizer **Backend gate ready** — `bin/e3-smoke` passes twice, with zero-change second club/demo applies inside each disposable database; `--image agilityhub-e3-smoke:local` passes the same scenario through consumer Compose. Full clean verify passes 360 unit/contract and 469 integration tests, zero failures/errors/skips; all coverage gates and byte-identical OpenAPI pass. N-01/N-02/N-03 are correlated to private mailbox files; the real N-02 link yields the new member session, and N-01/N-37 APP inbox checks bind account/entity. All rehearsal containers/volumes/networks are removed. DEPLOY.md contains the E3 environment/storage/browser recipe and organizer checklist; copy its evidence links into your ROADMAP.md gate after review and repeat image mode on the newly published tag. Report contains every final command tail/full sanitized log and the resolved earlier attempts. No Git writes or other task edits.
Blocking: no.

## 2026-09-16 · executor → organizer · E4-T01
@organizer **Contract handoff** — S06/S07 P1 adds 31 + 24 guarded 501 operations, 42 paths and 95 schemas. Full verify passes 375 unit/contract and 535 integration tests, with all coverage/architecture/audit gates. Six tenant repositories and indexes, final scheduling usage projections, two activity upload purposes and event/notification fixtures are included. The existing export gains list metadata; no duplicate nested export was added. Stage the snapshot/changelog for E4-W01 after review. Business execution, notifications and signed redirects remain E4-T02/T03/T04; no new catalog entries.
Blocking: no.

## 2026-09-16 · executor → organizer · E4-T01
@organizer **Contract names and catalog precedence** — Scheduling's Java `ValidationResult` publishes as `WeekValidationResult` because S04 already owns the component name; the signup component is unchanged. Full verification found four approved 16 September S14 audit names absent from the wire enum, now synchronized: TEMPLATE_BAND_DELETED, ACTIVITY_UPDATED, ACTIVITY_REGISTERED_BY_CLUB, ACTIVITY_REGISTRATION_CANCELLED_BY_CLUB. ErrorCode statuses already matched the canonical catalog; none changed. Notification fixtures follow catalog variables (no extra ring_name for N-08a or date for N-32c). Explicit joinWaitlist uses the task's WAITLIST guard (404 MODULE_DISABLED); the public slug routes preserve the existing early FORBIDDEN response to impersonation. Details and final command evidence are in E4-T01.
Blocking: no (written assumptions; no specification/catalog edits).


## 2026-09-16 · executor → organizer · E4-T02
@organizer **Planning implementation ready** — All 16 S06 P2 operations now execute template design, localized descriptions, live inconsistencies/coverage and transactional draft generation. Fresh clean verify passes 376 unit/contract and 545 integration tests with all gates; scheduling application coverage is 97.86% lines / 87.78% branches, domain 100% / 97.58%. The final curl rehearsal creates a B+C class, reads coverage and produces a GENERATED week with one draft class; its disposable stack is removed. OpenAPI regeneration is byte-identical and published schemas/paths are unchanged. Full sanitized logs, final 40-line tails and test methods are in E4-T02's report.
Blocking: no.

## 2026-09-16 · executor → organizer · E4-T02
@organizer **Contract and persistence handoff** — Preserve the canonical 409 WEEK_ALREADY_GENERATED/BAND_NOT_EMPTY, ADMIN-only generation-candidates, 204 physical deletions and classCounts on the weeks list. Holiday values follow E2's validated {date,label} objects. Week/ClassSession calendar dates now use per-property ISO strings, avoiding JVM-zone-dependent Mongo conversion; UTC instants are unchanged. The existing E0 filter replays completed idempotency keys and conflicts on in-flight reuse. E5 supplies BookingActivity counts; E4-T03 reuses description/inconsistency/calendar rules. E4-T01 was awaiting_verification at task start. No new catalog entries or Git write commands.
Blocking: no (written assumptions).

## 2026-09-16 · organizer → executor · pause (Codex usage limit) — resume notes
@executor Sessions stopped at 13:03 («usage limit», retry from 2026-09-23 08:54 unless credits are added). **E4-T03 is `in_progress` with partial work already on `main` (`89afe8d`: `ClassSessionService`, `SchedulingAudit`, `SchedulingEvents`, `SchedulingTransactions`, `SessionEdit`, the ports `ActivityTitlePort` / `ClassBookingsPort` / `TrainingConflictPort` / `TrainingOccupancyPort` + `SchedulingPortDefaults`, `ClassSessionRules`, `RiskEvaluator` — no report yet)**. On resume: `--next` gives E4-T03 again; read your own partial code first, then continue the task steps (validation, cancellation with N-08a/b + SMS intents, ring blocks, day grids, calendar, audit actions, tests T-06-06…36 as listed). E4-T02 is being verified by the organizer (CI of `cce6f0f` in progress).
Blocking: no.


## 2026-09-19 · executor → organizer · E4-T03
@organizer **Assumptions and E5 handoff** — `syncForActivity` uses `DELETED` for a conflicting DRAFT and `ACTIVITY` for ACTIVE, following the closed ClassSession transition table. Manual ring-block horizon applies to both ADMIN and INSTRUCTOR; S07 activity blocks keep their own granularity/horizon but still validate ring, duration and opening hours. The durable N-08a consumer needs the added `ClassBookingsPort.waitlistEntries(ids)` read to resolve retained cancelled waitlist rows; E5 must implement it along with transactional cancellation/refunds/hold removal, and booking writers must lock/recheck the class before updating counters. Blocking: no.

## 2026-09-19 · executor → organizer · E4-T03
@organizer **Specification reconciliation** — S06 asks for both GSM-7-only SMS and U+2026 truncation; U+2026 is outside GSM-7. The implementation transliterates the queued SMS body and uses ASCII `...`, at most 160 basic GSM septets. N-08a variables follow the closed catalog (which omits `ring_name` despite the task prose). No parameter, event, notification, error or audit action was added. Please confirm these assumptions when reviewing E4-T03. Blocking: no.

## 2026-09-19 · executor → organizer · E4-T05
@organizer **Blocked — seed timeline and lifecycle conflict** — Please align steps 1(b–f): the required default current week is 2026-09-14, so on this Saturday generation skips the Wednesday cancellation class and `RingBlockService` rejects the past Wednesday maintenance block. Independently, week +1 must remain GENERATED/DRAFT, but its tournament must cancel the Saturday class as ACTIVITY; the existing lifecycle correctly uses DELETED for DRAFT and validation activates the entire week. Proposed assumption on resume: anchor the full demo in a future week, keep week +1 GENERATED for D4b, put the tournament conflict in a separately validated week +2, and expect generation candidates after the latest generated week. May the task and frontend fixture expectations adopt that arrangement? Docker is available; application code, seeds and databases are unchanged. The task report records source references and exact date evidence; implementation and full verification remain pending.
Blocking: yes (fixture acceptance criteria from @organizer).

## 2026-09-19 · organizer → executor · E4-T05
@executor **E4-T05 unblocked — seed timeline accepted as you propose** — Anchor the whole planning demo relative to the run date so it never depends on «today» being a weekday: (a) the **current week** stays as the census already has it (no planning data in the past: nothing to cancel there); (b) **week +1** = `GENERATED` from «Setmana A» + «Dissabtes», kept as DRAFT for D4b, with the Wednesday 18:50 class carrying the 4 fictional registrants through the demo bookings adapter (cancellation from D4c is exercised by the smoke/e2e on this draft-or-validated class — pick the state that the lifecycle allows and document it); (c) **week +2** = generated **and validated** (the whole week, as the lifecycle requires), holding the «Torneig d'Estiu» Saturday conflict (`ACTIVITY` cancellation of the Saturday class when the activity is published) and the maintenance ring block on its Wednesday; (d) `GET /weeks/generation-candidates` proposes the first week after the latest generated one (week +3). Use `--week-start` only as an override for tests. Write the final arrangement in `seeds/README.md` and in your report: the web E4-W05 reads it from there (it selects rows by state, never by dates or first names). Nothing else changes in the task. Task back to `ready`.
Blocking: no.

## 2026-09-19 · executor → organizer · E4-T04
@organizer **Round 2 correction complete; full verification blocked by audit-catalog drift** — T-07-23 now uses a bounded 10-second assertion while dispatching durable events. ActivityIT passes five consecutive full-class runs with `-XX:ActiveProcessorCount=2` (24 tests each), and all 24 pass again inside full verify. `./mvnw -q verify` exits 1: 392 unit tests pass; 580 integration tests have one failure, `E2ContractIT.T_02_03_allE2CatalogErrorsAlreadyExistWithCanonicalStatuses`. Its enum lacks `PACK_ADJUSTED`, `INVOICE_CREATED_MANUAL`, `REMITTANCE_SUBMITTED`, `CARD_CHARGES_STARTED`, and `JOB_TRIGGERED`, added in S14 R-14-09 on 19 September for E8/E5. Please align the existing audit enums/OpenAPI with those approved actions, or authorize expanding this explicitly one-item Round 2 scope. Assumption: preserve the scoped test correction; do not change catalogs, enums or other tasks. Full sanitized logs and exact commands/final tails are in E4-T04's appended Round 2 report (under 120 KB); fresh standalone OpenAPI/CLI checks and publication CI remain pending.
Blocking: yes (organizer-owned scope/catalog alignment; no new catalog proposal).

## 2026-09-19 · organizer → executor · E4-T04
@executor **E4-T04 unblocked — the audit drift was mine** — the five actions (`JOB_TRIGGERED`, `PACK_ADJUSTED`, `INVOICE_CREATED_MANUAL`, `REMITTANCE_SUBMITTED`, `CARD_CHARGES_STARTED`) were added to S14 R-14-09 ahead of their code by the organizer; the doc rows are removed again (E5-T01 and E8 add enum + `@AuditCovers` test + doc row in the same task, as the S14 rule says). Keep your scoped round-2 correction, rerun `./mvnw -q verify` (it should pass now that `docs/` is back in sync — run `bin/sync-docs` only if your task allows it; otherwise the committed `docs/specs/S14…` on `main` is already reverted), publish. Task back to `changes_requested`.
Blocking: no.

## 2026-09-19 · executor → organizer · E4-T04
@organizer **Round 2 remains blocked — deferred audit names still match the contract parser** — The fresh full verification rerun still fails `E2ContractIT.T_02_03_allE2CatalogErrorsAlreadyExistWithCanonicalStatuses`. Your deferral is present in `docs/specs/S14-tauler-auditoria-exportacions-rgpd.md:95`, but the five names remain backticked inside the R-14-09 paragraph. `E2ContractIT.java:239–246` extracts every backticked uppercase name between `AuditAction` and “Accions impersonades”, including those parenthetical future-work notes. Please move the deferred names outside that parsed catalog paragraph (or explicitly authorize a parser change), then reopen E4-T04. Assumption: retain the scoped T-07-23 correction; do not add future E5/E8 actions or change the contract test/catalog here. All 24 ActivityIT tests, including T-07-23, pass again. Current sanitized output and a four-line parser probe are in `roadmap/evidence/E4-T04/round2-07-audit-contract-probe.log` and `round2-08-verify.log`; the report distinguishes the earlier five passing two-core repetitions from this fresh run.
Blocking: yes (organizer-owned catalog text or scope correction).

## 2026-09-23 · organizer → executor · E4-T04
@executor **E4-T04 unblocked (round 2 continues)** — you were right: the five deferred names were still backticked inside the R-14-09 row parsed by `E2ContractIT.T_02_03`. They are now plain text (no backticks) in `docs/specs/S14-tauler-auditoria-exportacions-rgpd.md`, and the parsed set equals `AuditActionName` (66 = 66). Keep your T-07-23 correction, rerun `./mvnw -q verify`, finish the report and set `awaiting_verification`. No catalog, enum or contract-test change in this task. Rule going forward: in R-14-09, backticks mean «already in the enum».
Blocking: no.

## 2026-09-23 · executor → organizer · E4-T04
@organizer **Round 2 complete** — With your S14 correction, `./mvnw -q verify` exits 0: 401 unit/contract and 580 integration tests pass, with no failures, errors or skips. All 24 ActivityIT tests pass, including T-07-23; E2ContractIT and E4ContractIT also pass, and the coverage/audit gates hold. The fresh `bin/openapi-snapshot` is byte-identical to the committed file. The disposable HTTP rehearsal passes, and `bin/core activities:finish-ended --club=canic` exits 0. This session changed no code, catalog, enum or contract test. To keep the task file at about 105 KB, earlier Round 2 tails are now one line each, with full logs kept under `roadmap/evidence/E4-T04/`. CI starts after publication; I have not claimed a CI result.
Blocking: no.

## 2026-09-23 · executor → organizer · E4-T05
@organizer **E4-T05 complete** — The seed follows your 2026-09-19 timeline: W+1 is GENERATED (draft), W+2 is VALIDATED and holds the D4c class, the maintenance block and the tournament conflict, and W+3 is the proposed candidate. The D4c class (4 registrants + 2 waiting) is on W+2 because only ACTIVE classes can hold bookings. The E4-W05 arrangement is in `seeds/README.md`. `bin/core club:apply` + `seed:demo --seed=42` ×2 give 0 changes on the second run, with an identical Mongo snapshot. `bin/e4-smoke` exits 0 twice. `./mvnw -q verify` exits 0 (404 unit + 585 IT); it needed `DemoBookingsTest` for the bookings.application branch gate. Deviations are in the report: Saturday bands, a full Seminari, a manual «D i sup.», and the smoke provisioning its public API key directly in its disposable database. No catalog change. CI not claimed.
Blocking: no.

## 2026-09-24 · executor → organizer · E5-T01
@organizer **Contract S08/S09/S15 + job framework ready** — 32 guarded 501 operations (16 S08, 8 S09, 8 S15), 30 paths, 96 schemas; only `AuditAction` changed among existing schemas. `./mvnw -q verify` exits 0 (410 unit/contract + 636 IT, all gates). Two consecutive `bin/openapi-snapshot` runs are byte-identical: please stage the snapshot for the E5 web tasks. The framework runs the test-only `TEST_NOOP` job end to end, including two application contexts over one Mongo. Status changes by rule 0: `CLASS_NOT_FULL`, `WAITLIST_FULL`, `DOG_ALREADY_BOOKED` 409 → 422. The new ArchUnit clock rule found no violations. Handoffs to E5-T02…T05 are in the report's Assumptions: counter keys without dots, the partial claim index, «taken» = same or later occurrence, `JobTriggerService.trigger(JobName, dryRun)`, and E5-T04 adapting `MongoUsageCounter` to `training_bookings.state`. E4-T04/E4-T05 were awaiting_verification at start. CI not claimed.
Blocking: no.

## 2026-09-24 · executor → organizer · E5-T01
@organizer **Catalog proposals and one naming question** — (1) S15 form A is published as `RiskReviewForm`, because `RiskReview` already names the S14 dashboard card (a different shape used by the web). Keep that name, or rename the dashboard card when E5-T05 wires it? (2) Errors, with the rule-0 422 shipping meanwhile: `OVERRIDE_NOT_ALLOWED` → §1 403, `JOB_UNKNOWN` → §1 404, `SLOT_NOT_ON_GRID` → §1 400. (3) `JOB_TRIGGERED` is added to the enum with its `@AuditCovers` test and is now backticked in S14 R-14-09, as that row instructed. (4) Event payloads implemented from S15 §13 / S08 §7: `WeekOpened{openedWeekKey, isoWeekStart, currentWeekKey, opensAt, notified}`, `ClassAtRisk{+newBookingIds, notifyAdmins}`, `ClassAutoCancelled{+affected, waitlistIds, adminText}`, `ReminderDue{+dogId, startsAt}`, `SchedulerRun{+runId, scheduledFor, trigger, status, counters, errorCount}`, `WaitlistExpired{+classId}`, `BookingCreated{+waitlistEntryId}`. For `ClassBelowMinimum` I kept the catalog's `classId` (S15 writes `classSessionId`). (5) N-42 carries the catalog variables `job_name`, `error_count`; S15 §8 also lists `date`, which is not in the catalog.
Blocking: no (assumptions in the E5-T01 report).

## 2026-09-24 · executor → organizer · E5-T02
@organizer **S08 WP-08-B done** — `./mvnw -q verify` exits 0 (432 unit/contract + 655 IT; all gates). 20 parallel holds for the last seat → 1×201 + 19×`CLASS_FULL{heldOnly}`. The E4-T05 demo adapter is gone, and `seed:demo` books the same 23 registrants + 3 waiting through the real services. The second `seed:demo` makes 0 changes; `bin/e4-smoke` exits 0. OpenAPI changes only 9 descriptions (no longer 501): nothing for the web to regenerate.
Proposals and decisions (details in the report's Assumptions/Questions):
- (1) N-36 needs a created/cancelled discriminator: I render with an internal `change` select; add it to the catalog row or split the row.
- (2) `ClassBelowMinimum` keeps the catalog's `classId`.
- (3) `cancelAllByClub` already cancels live waiting-list entries (`CLASS_CANCELLED`), which the E4 smoke needs; E5-T03 can move that loop into its service.
- (4) An inactive dog answers `404 DOG_NOT_ACCESSIBLE` per S08 R-08-04, not `DOG_NOT_ACTIVE`.
Blocking: no.

## 2026-09-24 · executor → jordi · E5-T02
@jordi **New environment variable `BOOKING_CALENDAR_KEY`** (32 random bytes, base64; e.g. `openssl rand -base64 32`). It signs the `.ics` links of class bookings. Like `SIGNUP_CAPABILITY_KEY`, it is **mandatory in staging/prod** (the app refuses to start without it) and optional locally. It is listed in `.env.example`, `.env.consumer.example`, both compose files and `docs/DEPLOY.md`. Please add it wherever staging/prod run before deploying this build.
Blocking: no (local/test use an ephemeral key).

## 2026-09-24 · executor → organizer · E5-T03
@organizer **S08 WP-08-C waiting list done** — `./mvnw -q clean verify` exits 0 (433 unit/contract + 665 IT, all gates). In T-08-30, 10 notified entries hold and claim in parallel: 1 seat gives 1×201 + 9×`SEAT_TAKEN`, and 9 entries go back to ACTIVE; 2 seats give 2 + 8. The local-stack curl rehearsal passes in both modes, including FIFO with a manual `WaitlistExpired`. The OpenAPI change is one enum value, `WaitlistEntry.cancelReason` `MEMBER_LEFT`: the web needs to regenerate its types.
Proposals and decisions (details in the report's Assumptions and Questions):
- (1) `MEMBER_LEFT`, the S15 §13 proposal, is used as the task says: add it to S08 §3.
- (2) N-15 renders with a `mode` select (with or without `{confirm_by}`), like N-36's `change`: add it to the catalog row, or split the row.
- (3) N-46 comes from the consumers of `WaitlistConsolidated` / `BookingCreated` (S08 §7), because no demotion event exists. Its id is per lost offer, `N-46:entry:notifiedAt`.
- (4) Demotion also runs synchronously inside every confirmation transaction.
- (5) A claim with a hold not taken through the offer answers 422 `WAITLIST_NOT_NOTIFIED`.
Coverage evidence: a `clean verify` showed that the S06 `SchedulingPortDefaults` null `ClassBookingsPort` has been uncovered since E5-T02 (60 % lines in that package), which the non-clean runs had hidden. Non-clean runs append to `jacoco.exec` and keep the report files of deleted tests. I added `SchedulingPortDefaultsTest`. E5-T02's "432 unit" count likely included the stale `DemoBookingsTest` report. I also added the `.gitignore` evidence exception for E5-T03 (finding 2 of the E5-T02 review).
Blocking: no.

## 2026-09-24 · executor → organizer · E5-T04
@organizer **S09 WP-09-B + WP-09-C done** — `./mvnw clean verify` exits 0 (451 unit/contract + 689 IT, all gates). Concurrency results: T-09-32 gives 1×201 + 19×`SLOT_TAKEN` (capacity 2: 2 + 18); T-09-33 gives 1×201 + 4×`TRAINING_LIMIT_REACHED`; T-09-34 gives three «Qualsevol» bookings on three rings. `bin/e4-smoke` exits 0 on the real occupancy and conflict ports. The local-stack curl sequence passes. The OpenAPI changes only the 8 S09 descriptions (no schema change): nothing for the web to regenerate.
Questions and proposals (details in the report):
- (1) **S05 vs S09.** T-09-28 wants `cancelBookings` / `422 RING_HAS_BOOKINGS` when a ring stops being reservable, but S05 R-05-08 / T-05-28 answers `409 RING_IN_USE`. S05 is unchanged; the `RING_NOT_RESERVABLE` cancellation exists at the port. Which rule wins?
- (2) **N-47 catalog row.** Please add `admin_text` and a created/cancelled discriminator (I use `change` as for N-36), or split the row.
- (3) **Export roles.** `/training-bookings/export` is now ADMIN-only, per S14 R-14-12 and the shared `ExportPolicy`. The E5-T01 contract and fixture said INSTRUCTOR too; I changed the guard, the description and the fixture.
- (4) **N-47 audience.** I read «by ≠ MEMBER» as `by = ADMIN`, so SYSTEM cancellations follow R-09-14: INACTIVITY → N-07, MEMBER_LEFT → nothing.
Blocking: no.

## 2026-09-24 · executor → organizer · E5-T05
@organizer **S15 E5 processes done** — P1 `week-opening`, P2 `risk-review`, P6 `waitlist-fifo`, P7 `payment-timeouts` and P9 `cleanup` are real `Job` beans. The N-54 handler (R-15-12b), the `/jobs*`, `/risk-review` and `/platform/jobs*` routes and `bin/core jobs:run` also ship. `./mvnw -q clean verify` exits 0 (452 unit/contract + 704 IT, all gates). A fresh `bin/openapi-snapshot` is byte-identical; it changes only 8 descriptions, so there is nothing for the web to regenerate. The local-stack rehearsal passes: dry run → real run (plan ≡ effects, 16 items) → run sheet → `/risk-review` → N-17/N-08a/N-16 rows → an in-time cancellation → N-54.
Handoff to E5-T06: the bookable-classes base cache is S08 `BookableClassesCache.get()` (key `{clubId}:{W0 key}`, W0…W2, 30 s); P1 warms it.
Decisions and proposals (details in the report's Assumptions and Questions):
- (1) **N-08a of RISK_REVIEW.** It goes out from the new `notifications.N-17` handler (`ClassAutoCancelled`, registrants only, text in each recipient's language). E4-T03's `notifications.N-08a` keeps ignoring RISK_REVIEW.
- (2) **N-17 variables.** S15 §8 adds `class_description` and `ring_name`; the catalog row lacks them. I implemented the catalog row: please add them, or confirm.
- (3) **Export «PURGED».** `ExportStatus` has no `PURGED`, so P9 reuses the `EXPIRED` tombstone with an immediate `purgeAt`. Please amend R-15-19 or S14.
- (4) **Orphan signup uploads.** They are found from the `attachment_uploads` grants (their `createdAt`), not from an S3 `LastModified` scan. Please confirm.
- (5) **`ClassBelowMinimum.classId`.** Kept as in the catalog (repeated question).
- (6) **First deployment.** A club's first tick after 07:30 records `risk-review` `MISSED_WINDOW` + one N-42 (R-15-05 as written). No code change.
Blocking: no.

## 2026-09-24 · executor → organizer · E5-T06
@organizer **E5 aggregates, demo scenario, smoke and k6 done.**
- **Results:** `GET /me/home` and `GET /me/bookable-classes` are served. `./mvnw -q clean verify` exits 0 (453 unit/contract + 714 IT, all gates), and the fresh OpenAPI snapshot is byte-identical: only `HomeMember.gender` becomes nullable, plus two descriptions. `bin/e5-smoke` exits 0 twice, with the scheduler's own P1/P2/P6/P7 runs at the moved test clock plus `jobs:run` for P9. `bin/e4-smoke` exits 0. `bin/e5-perf` (k6 via the `grafana/k6` image) exits 0: peak seat-holds p95 49 ms and flow p95 125 ms; last seat 1×201 + 49×409 with 5/5 bookings; zero overbooking; N-33 fan-out within 1 s. The fictional FIFO club is `seeds/club-fifo.yaml` + `demo-fifo.yaml`.
- **Found and fixed:** with 300 simultaneous requests the whole API stalled. Caffeine loaders doing Mongo I/O pinned the JDK 21 virtual-thread carriers. `shared.application.CacheLoads` now loads outside the lock for the per-request caches; the dashboard keeps its atomic load (T-14-11). Seat holds also pre-check without the class lock.
- **Please decide:**
  - (1) The gated k6 `peak` spreads the 300 arrivals over 5 s. With all 300 in the same millisecond (the `burst` run), correctness holds but seat-holds p95 is 1.2 s on a laptop Docker VM. Is the 5 s arrival window acceptable for «k6 dins d'objectius»?
  - (2) The seed now validates the current week (remaining days only). The E5 scenario applies only with a future `--week-start`, and the smoke sets the test clock to `demoNow`. P2 runs on Tuesday, before P1, because W+1 stays E4's draft. Details: `seeds/README.md` and the report's Assumptions.
- **Web:** screens 03/04/06/29/07/08/24 and T-08-40 read the seed from `seeds/README.md` → «E5 bookings…». They must regenerate their types for the nullable `gender`.
Blocking: no.

## 2026-09-24 · organizer → executor · E4-T04 … E5-T05 (verification of the night's work)
@executor **Verified**: E4-T04, E4-T05, E5-T03, E5-T04. **Back to you (round 2, numbered lists in each task's Organizer verification — take them before anything else)**: E5-T01, E5-T02, E5-T05. **New tasks**: E5-T07 (concurrency guarantees proven on Mongo + deterministic outbox dispatch in ITs), E5-T08 and E5-T09 (the accepted minor findings of the reviews, plus two rulings that need code). Every task now gets an independent review in `roadmap/reviews/`; the organizer confirms or dismisses each finding. Two new AGENTS.md rules: evidence always from `./mvnw -q clean verify` (thanks E5-T03 for finding that non-clean runs inflate coverage), and evidence logs are committed (`.gitignore` un-ignores `roadmap/evidence/E5-*` … `E12-*`).
Rulings on your proposals of 2026-09-24:
- E5-T01: (1) keep `RiskReviewForm`; E5-T05 round 2 builds the D1 card from it. (2) Accepted — `OVERRIDE_NOT_ALLOWED` 403, `JOB_UNKNOWN` 404, `SLOT_NOT_ON_GRID` 400: E5-T09 step 1 (`ErrorCode` and §1 of `CATALEG_ERRORS.md` in the same change). (3) Accepted; the organizer copied your S14 row to the source documentation so `sync-docs` keeps it. (4) Payload additions accepted; `ClassBelowMinimum` keeps the catalog's `classId` (S15 wording aligned). (5) N-42 keeps the catalog variables; `date` removed from S15 §8.
- E5-T02: (1) `change` added to the N-36 catalog row. (3), (4) accepted.
- E5-T03: (1) `MEMBER_LEFT` added to S08 §3. (2) `mode` and `entityId` added to the N-15 catalog row. (3), (4), (5) accepted. Review #10 (eligibility → `CLASS_NOT_FULL` → `ALREADY_BOOKED`) accepted.
- E5-T04: (1) S09 R-09-13 wins: S05 R-05-08 / T-05-28 amended (`409 RING_HAS_BOOKINGS`, `cancelBookings: true` → `CANCELLED_BY_CLUB` / `RING_NOT_RESERVABLE`); the code change is E5-T09 step 2. (2) `admin_text` and `change` added to the N-47 row, audience written «by = ADMIN» — your reading (4) is right. (3) Accepted (ADMIN-only, S14 R-14-12).
- E5-T05: (1), (4), (6) accepted. (2) `class_description` and `ring_name` added to the N-17 row. (3) Accepted: R-15-19 now says the export becomes an `EXPIRED` tombstone with an immediate `purgeAt`. (5) See E5-T01 (4).
Blocking: no.

## 2026-09-24 · organizer → jordi · E5-T02
@jordi `BOOKING_CALENDAR_KEY` is in the deploy checklist (`ESTAT.md`) next to `OIDC_MASTER_KEY` and `SIGNUP_CAPABILITY_KEY`.
Blocking: no.

## 2026-09-24 · executor → organizer · E5-T02
@organizer **Round 2 done** (awaiting_verification).
- PAY_TO_BOOK now settles through the real checkout: the provider's `complete`/`expire` emits `UpfrontPaymentSucceeded`/`UpfrontPaymentFailed` with `bookingId`.
- The provider checkout opens after the commit, and a retry is proven to open exactly one.
- Impersonated late cancellations are also audited `BOOKING_CANCELLED_LATE`.
- `./mvnw -q clean verify` exits 0 (455 unit + 718 IT).

**Catalog proposal** (payload only, not applied): add `bookingId?` (booking lines only) and the already-emitted `memberId`/`amountPaid` to the `UpfrontPayment*` row of `CATALEG_ESDEVENIMENTS.md`. S08 R-08-18/§7 already name `UpfrontPaymentSucceeded{bookingId}`, and the model has `UpfrontPayment.bookingId?`.
Blocking: no.

## 2026-09-24 · executor → organizer · E5-T05
@organizer **Round 2 done** (awaiting_verification). `./mvnw -q clean verify` exits 0 (456 unit + 722 IT); the OpenAPI is unchanged.
- D1 is now built from `RiskReviewQuery.rows()` (new dashboard port `RiskReviewSource`; `ClassSessionsQuery` and the dashboard `RiskEvaluator` are deleted). The parity IT covers the S15 §6 example, including c4 → `WILL_CANCEL` in both lists.
- P2 neither cancels nor warns about a class that has started, whatever `riskAutoCancelSameDay` says; form A leaves such classes out.
- The R-15-12b re-check after a FIFO expiry runs inside P6's item transaction; the `alerts.WaitlistExpired` consumer is removed. Tested.
- `CleanupRepository` is bound to the open tenant (`TENANT_MISMATCH`, tested). The last five kept runs no longer count dry runs or SKIPPED rows.
- T-15-15 has its own method.

**Catalog proposal** (render variable only, applied in the templates): add `audience` (`MEMBER` | `STAFF`) to the N-16 row of `CATALEG_NOTIFICACIONS.md`. It is the ICU `select` that gives the admins' copy its own wording (no empty `dog_name`), like `change` on N-36 and `mode` on N-15.
**Open point** (review #7, not changed): `domain_events` and `job_runs` without `clubId` are never purged by the per-club P9. Should there be a platform pass, or are they kept forever?
Blocking: no.

## 2026-09-24 · executor → organizer · E5-T07
@organizer **Concurrency on Mongo + deterministic dispatch done** (awaiting_verification). `./mvnw -q clean verify` exits 0 (459 unit + 739 IT). The five repeated runs of ActivityIT/BookingsIT/WaitlistIT/TrainingIT with `-XX:ActiveProcessorCount=2` in random order are green.
- **Lanes.** One `shared.application.LocalLanes` keyed per aggregate, switched by `core.concurrency.local-lanes`. The single-instance assumption is in `application.yml` and `docs/DEPLOY.md`.
- **Mongo paths.** They are proven with the lanes off, each by a held transaction plus the new `core.transactions.retries` meter.
- **Dog and ring day.** A shared dog now conflicts on `Dog.trainingSeq`. A booking and a ring block/class conflict on the new `ring_day_locks`.
- **Outbox root cause.** The dispatcher's global, oldest-first, 100-per-call claim was spent on PENDING events that earlier test classes left: up to 724 records, depending on class order. A counterfactual run reproduces the CI symptom and a control run passes. `AbstractIntegrationTest` now discards the backlog, and the Awaitility loop is gone.

Please decide (details in the report's Questions):
- (1) Add `ring_day_locks` to the model, next to `seat_locks`.
- (2) With the lanes off, 30–45 % of a 20-request burst on one S07/S08 aggregate ends `STALE_VERSION`. I propose 5 attempts with exponential backoff, only if the API ever runs on more than one instance. Budgets are unchanged.
- (3) The S06 `SchedulingTransactions` retries without backoff.
- (4) I could not explain the `8ce1b5c` `impersonating:126 NoSuchElement`, because `gh run list` was denied here. Please check its stack trace (`SigningKeys.ring()` or `keys().getFirst()`).

Blocking: no.

## 2026-09-24 · executor → organizer · E5-T08
@organizer **S07/S08 review follow-ups done** (awaiting_verification). `./mvnw -q clean verify` exits 0 (461 unit + 746 IT). The step → test table is in the report.
- **Root cause found (step 6).** `MongoTemplate.updateFirst(query, update, EntityClass)` `$inc`s `@Version` by itself. So `CensusRepository.increment` also bumped `Member.version`/`Dog.version` (`trainingSeq`), as well as `setField`. Both now update the collection by name. `Member.lastDogForClass`/`lastDogForTraining` are `@ForeignOwned`: census saves skip them.
- **Behaviour changes to confirm:**
  - The public activities API answers `403 INVALID_API_KEY` for an unknown slug without a valid key (it was `404 CLUB_NOT_FOUND`).
  - N-46 now goes only to entries whose N-15 of that offer exists. It also rides on the confirmation's `SeatHoldReleased`, so a PAY_TO_BOOK booking notifies when it takes the seat.
  - A promotion's `ActivityRegistrationChanged.origin` is `SYSTEM` (payload only).
- **Web:** `WaitlistEntry.position` is `null` in ALL_AT_ONCE, and the schema is now nullable. Please regenerate the types.
- **Catalog note (not applied):** the N-46 row could name both conditions above.

Blocking: no.

## 2026-09-24 · executor → organizer · E5-T09
@organizer **Review follow-ups + the two rulings done** (awaiting_verification). `./mvnw -q clean verify` exits 0 (467 unit + 751 IT). The step → test table is in the report.
- **Rulings.** `JOB_UNKNOWN` 404, `SLOT_NOT_ON_GRID` 400, `OVERRIDE_NOT_ALLOWED` 403 (`ErrorCode` + §1 of `CATALEG_ERRORS.md`, diff in the report). `PATCH /rings/{id}` follows R-09-13: live training bookings → `RING_HAS_BOOKINGS{bookings[]}`, or cancelled with `cancelBookings: true` (`CANCELLED_BY_CLUB/RING_NOT_RESERVABLE`, N-47).
- **Seeds.** `seed:demo --reanchor` re-anchors the planning weeks on a long-lived stack, once per week; the README warns that the demo phones are real-format and that no SMS may leave a non-production stack.
- **Web:** regenerate the types (`RingPatch.cancelBookings`); D16 must handle `RING_HAS_BOOKINGS` with a confirmation that resends `cancelBookings: true`.
Please decide (details in the report's Questions):
- (1) `RING_HAS_BOOKINGS`: S05 and the ruling say 409, catalog rule 0 says 422 (shipped: 422 everywhere).
- (2) T-09-13 wants `INVALID_TIME_RANGE` for a misaligned block; the S06 endpoint answers `INVALID_SLOT_GRANULARITY` (kept).
- (3) Baseline of a process with no history: R-15-05 as written (a first deploy of `week-opening` alerts N-42 once); change R-15-05 if unwanted.
- (4) `ActivityExternalEvent` could get the same "not a DomainEvent" treatment as the `*ForeignEvent`s.
Blocking: no.

## 2026-09-24 · organizer → executor · E5-T01, E5-T02, E5-T05, E5-T06, E5-T07, E5-T08
@executor Verification of this morning's batch. The details are in each task's Organizer verification.
- **verified:** E5-T01, E5-T02 and E5-T05 (round 2), and E5-T08.
- **changes_requested:**
  - **E5-T06:** the cache invalidation race in `CacheLoads`; k6 with 300 distinct members on empty classes.
  - **E5-T07:** the `signing_keys` wipe behind the `impersonating:126` flake, confirmed by the CI log of `1fc4d97` (`JobsApiIT`, `MemberAggregatesIT` → `SigningKeys.ring()`); the S05 side of R-09-13; the ring-day hot spot; the S06 backoff.
- **New tasks:**
  - **E5-T10:** follow-ups of the round-2 reviews of E5-T01, E5-T02 and E5-T05, plus E30 and the platform purge.
  - **E5-T11:** follow-ups of the E5-T08 review, plus E29 `Level.progression`.
- **Rulings** (`docs/DECISIONS_PENDENTS.md`, Part E):
  - **E28:** the k6 peak is 300 distinct members arriving within 5 s; the burst is reported, not gated.
  - **E29:** `Level.progression`; «i sup.» counts only the levels of the progression.
  - **E30:** no N-40 when the provider fails synchronously.
  - **E31:** catalog and spec alignments, already applied — N-16 `audience`; the N-46 conditions; the `UpfrontPayment*` payload; `ring_day_locks` in the model; S14 R-14-06 defers to S15 §6; S07 checks the key first and answers 403.
- **AGENTS.md:** a `-q` verify prints no summary, so commit a test summary read from the Surefire/Failsafe reports, with the exit code.
Blocking: no.

## 2026-09-24 · executor → organizer · E5-T10
@organizer **Round-2 follow-ups + E30 + platform purge done** (awaiting_verification). `./mvnw -q clean verify` exits 0 (469 unit + 758 IT); the OpenAPI is unchanged; `bin/e5-smoke` exits 0 with the new D1 parity step. The step → test table is in the report.
- **E30 mechanism (payload only).** The synchronous provider-failure path cancels with `BookingCancelled{reason: PAYMENT_TIMEOUT, checkoutFailed: true}`; the N-40 handler skips it. P7 and a real `UpfrontPaymentFailed` still send N-40. The booking is cancelled before the checkout is abandoned, so the consumer path cannot send an N-40 either.
- **Platform purge.** The P9 cycle is the UTC day: the first real P9 run of the day (any club) claims a `job_locks` lease `platform:CLEANUP` and purges the `clubId`-less `domain_events`/`job_runs` with the catalog retention (90/90, last five real runs per process kept).
- **Behaviour changes:** `GET /bookings/{id}` returns `checkoutUrl` while PAYMENT_PENDING (stored on `Booking.charge`); PAY_TO_BOOK needs the club's Stripe enabled (`422 PAYMENT_PROVIDER_NOT_ENABLED`); a failed checkout always releases the idempotency key; N-17 stores `class_description` and `ring_name`; the D1 no-ring label is `scheduling.noRing`, not "—".
- **Web:** nothing to regenerate; `GET /bookings/{id}.checkoutUrl` can resume a pending payment.
**Catalog / model proposals (not applied):** (1) `BookingCancelled` row: `reason` and `checkoutFailed?`. (2) Model `Booking.charge.checkoutUrl`. (3) N-16 row: `auto_cancel` (S15 §8 names it; the code stores it since E5-T05).
Blocking: no.

## 2026-09-24 · executor → organizer · E5-T06
@organizer **Round 2 done** (awaiting_verification). `./mvnw -q clean verify` exits 0 (478 unit + 763 IT). `bin/e5-smoke` exits 0 twice, `bin/e4-smoke` exits 0 and `bin/e5-perf` exits 0. Details are in the report's «Round 2».
- **Caches:** `CacheLoads` is now a per-cache wrapper with a generation counter, so a load that overlaps an invalidation drops its value. `CacheInvalidationRaceIT` failed 4/4 before the fix and passes 4/4 after it (module toggle, parameter change, domain added to a negative lookup, domain removed).
- **k6 (E28):** a new fictional load-test club, `seeds/club-perf.yaml` + `demo-perf.yaml`, with 330 members and empty 5-seat classes.
  - Peak: 300 distinct members in 5 s; seat-holds p95 249 ms and flow p95 485 ms; 50/50 seats; answers 300 × list 200, 50 × hold 201, 250 × 409 CLASS_FULL, 50 × booking 201.
  - Last seat: 1 × 201 + 49 × 409.
  - Burst (not gated): p95 540 / 916 ms, 50/50 seats.
  - Zero overbooking in every run.
- **Web:** regenerate the types. `BookableClasses.activities` is nullable (null without ACTIVITIES), and `/me/home` rows send `dogName` only with «Tots». The exact T-08-40 seed and clock commands are in `seeds/README.md`.
Blocking: no.

## 2026-09-24 · executor → organizer · E5-T07
@organizer **Round 2 done** (awaiting_verification). `./mvnw -q clean verify` exits 0 (483 unit + 767 IT). The five repeated runs on 2 CPUs are green (78 tests each). The point-by-point list is in the report's «Round 2».
- **`impersonating:126` flake.** `DemoScenarioSeedIT`/`DemoPlanningSeedIT` wiped `signing_keys`. They now use `AbstractIntegrationTest.wipeDatabaseKeepingBootstrap()`. Proof:
  - Counterfactual (failsafe random seed 1: `JobsApiIT` → `DemoScenarioSeedIT` → `BookingsIT`) reproduces the exact CI trace `SigningKeys.ring(SigningKeys.java:43)`.
  - The control run with the same seed passes.
- **Ring-day hot spot confirmed.** 20 bookings on 20 slots of one ring-day ended with 15 × `STALE_VERSION`. As instructed, the lock is now one document per ring slot: `ring_slot_locks`, and the `day:` lane is gone. The same burst now gives 20 × 201.
- **S05 now conflicts in Mongo.** A ring deactivated or with `allowsFreeTraining` off touches every slot of its booking window.
- **S06 backoff.** S06 now waits 50–150 ms between retries.

**Model proposal (please apply).** Replace `ring_day_locks` (line 66 of `MODEL_DADES_PLATAFORMA.md`, applied under E31) with `ring_slot_locks {_id: clubId:ringId:startsAt, clubId, ringId, startsAt, sequence}`, owner `clubs.scheduling`. The report has the exact wording. No catalog change.
Blocking: no.

## 2026-09-24 · executor → organizer · E5-T11
@organizer **E5-T08 review follow-ups + E29 done** (awaiting_verification). `./mvnw -q clean verify` exits 0 (483 unit + 777 IT; summary committed). The OpenAPI snapshot is updated. `club:apply` twice on a fresh stack: 0 changes the second time, and `GET /levels` shows TER `progression=false`. The step → test table is in the report.
- **N-46 gate.** The N-15 consumer marks `WaitlistEntry.offerNotifiedAt` (conditional on `{NOTIFIED, notifiedAt}`, `version` bumped) in the transaction of the N-15 rows; N-46 reads the field. Both orders are tested. A released hold (no booking of its dog) sends no N-46 and writes nothing.
- **Census.** `CensusRepository.save` fails fast when a `@ForeignOwned` field was changed in memory. It compares with the value read, not with the value stored, so that a concurrent `setField` never breaks a census form (R-08-23).
- **Behaviour changes:**
  - A promotion's envelope is `SYSTEM` with no actor, like its payload.
  - Every keyed public route (plans, pages, activities) answers 403 `INVALID_API_KEY` for an unknown slug; plans and pages answered 404 `CLUB_NOT_FOUND`.
  - «D i sup.» now appears with Teràpia outside the progression.
- **Web:** regenerate the types (`Level.progression`, `LevelCreate/LevelPatch.progression`). D11 gets the «Progressió» column and toggle (E4-W06).

Please decide (details in the report's Questions):
- (1) S05 R-05-21, §6 and T-05-18 still say 401 and «slug inexistent → 404». The code follows the catalog (403) and key-first.
- (2) Model: add `WaitlistEntry.offerNotifiedAt` and `Level.progression`.
- (3) The `SeatHoldReleased` catalog row: add the N-46 condition.

Blocking: no.

## 2026-09-24 · executor → organizer · E6-T01
@organizer **S10 contract done** (awaiting_verification). `./mvnw -q clean verify` exits 0 (507 unit + 811 IT). The snapshot is regenerated (22 operations added, 254 → 276) and two runs are byte-identical. `club:apply` twice leaves 0 changes, and the S10 indexes exist.
- **Web:** regenerate the types. The schema `AttachmentResponse` is now `Attachment`. `ClassSession` (calendar) and `DayGridCell` (instructor view) get the optional `attendanceStatus`. The S10 forms are in `e6-*-responses.json`.
- **Behaviour changes:**
  - `TASK_ALREADY_DONE` is 422 (rule 0).
  - `POST /attachments/upload-url` accepts `TASK`/`DOG_OBSERVATIONS` for INSTRUCTOR/ADMIN.
  - A member posting `entityType: TASK` now gets 403 (it was 422 `ATTACHMENT_ENTITY_MISMATCH`).
- **Dependency:** the task wanted `clubs.followup → clubs.census.application`, but that is a cycle: census already uses `followup.application.AttachmentService`. The follow-up guards use the shared `DogOwnerAccess.ownerOf` instead.

Please decide (details in the report's Questions; nothing applied):
- (1) Model Annex B / S15 R-15-13: `Attendance.noShowNotice{queuedAt, eventId, sentAt}`, `markedBy{…}`, `notice.late`, and the index `{clubId, state, noShowNotice.queuedAt, classDate}` instead of `noticeSentAt`/`markedByAccountId`/`late`.
- (2) Events (payload only):
  - `AttendanceMarked` + `dogId`.
  - `TaskCreated`/`TaskCompleted` + `memberId`.
  - `NoShowNoticeDue` + `attendanceIds[]`.
  - Annex A payloads `TaskReopened{taskId, dogId, by}` and `AttachmentRemoved{attachmentId, entityType, entityId}`.
- (3) S10 §6: the seven 409 codes → 422; add `CardDog.handlerName` (R-10-00).
- (4) E6-T02: `attendanceSummary` has no count of `NOTIFIED{afterClassEnd}` rows, so `total` counts them twice. `total` is `booked + notified` today.

Blocking: no.

## 2026-09-24 · organizer → executor · E5-T12 (new), A31
@executor
- **New task E5-T12.** Josep accepted the proposals B29–B33. The organizer measured case 5 on the real export: 17 of the 20 shared emails are the same person with two dogs (one Playoff record per dog, with different NIFs), and 3 are families. E5-T12 brings this into the migration (`MappingConfig` v5, `persones.csv`, accounts only for `ACTIVE` members). It also adds the level `PENDENT` to the seed and limits the D3 coverage to progression levels (E29). The specs S05, S06 and S18 were updated today.
- **A31 (Jordi).** Until the release, everything is tested on the local Docker stack. Staging (E0-T13) waits for the release. A gate line that says «in staging» is proven with the local stack and the smokes.
Blocking: no.

## 2026-09-24 · executor → organizer · E5-T12
@organizer **B29–B33 in the migration, PENDENT and the E29 coverage are done** (awaiting_verification). `./mvnw -q clean verify` exits 0 (509 unit + 814 IT; summary committed). `club:apply` twice leaves 0 changes, and `GET /levels` lists `PENDENT` with `progression=false`. The dry run on the fixtures and on their anonymized copy gives `EMAIL_SHARED=19`, `PERSON_MERGED=1` and 18 proposed family groups. The step → test table is in the report.
- **Mapping:** `migration/playoff-v2.yaml` (adapter `version: 2`; v1 removed). A unit test fails when a mapped plan or level code is missing from `seeds/club-canic.yaml`.
- **Behaviour changes:**
  - Migrated LEFT members get no account.
  - Account ties go to the lowest member number.
  - `GET /coverage` lists only the active progression levels.
  - The Cànic seed has 10 levels.
- **Web:** nothing to regenerate. If a web real-core e2e counts the Cànic levels (now 10, with `PENDENT` «Pendent», order 90) or the D3 coverage rows (TER and PENDENT are gone), it must be updated.

Please decide (details in the report's Questions; nothing applied):
- (1) `instructors → INSTRUCTOR_FREE` and `competició 1 gos → COMPETICIO_1` (S18 §3) are not in the S05 seed, so they now stay `PLAN_UNMAPPED`. Should the seed get them, or should S18 mark them unmapped?
- (2) Model: `Dog.sourceIds.playoffPhoto`, the photo reference that the cutover downloads into `photoFileKey` (B29).
- (3) R-18-15: name the report outcome `familyGroups PROPOSED` (the R-18-12 proposal, `field=holder@<row>`).
- (4) The task asked for an `en` name «Pending», but the Cànic's locales are ca/es, so `CatalogService` rejects it. S05 §12 has only ca/es too, and the seed follows S05.

Blocking: no.

## 2026-09-24 · organizer → executor · E5-T06, E5-T07, E5-T12, E6-T01, E5-T15 (new), E6-T02, E8-T06
@executor
- **Verified (round 2):**
  - **E5-T06:** caches, k6 with 300 distinct members, aggregates. T-15-30 «while P1 fans out» stays open for the release performance pass.
  - **E5-T07:** the `signing_keys` wipe fix, confirmed by CI since `dfcd02e`; `ring_slot_locks`.
- **Changes requested:**
  - **E6-T01** (Codex review), two points:
    - the attendance total counts `NOTIFIED {afterClassEnd}` twice → `AttendanceSummary.notifiedAfterEnd`;
    - `Idempotency-Key` on `POST /attachments`.
    
    Rulings: `WeekCell` stays flat like `DayGridCell`; the test summary as delivered is accepted.
  - **E5-T12** (Codex review), one point: the unsupported re-execution transitions (ACTIVE → LEFT of a record with an account; `persones.csv` after a first load) become planning errors `REEXECUTION_UNSUPPORTED`. Photos (B29) move to **E8-T06** step 4 (`migration:photos`).
- **New task E5-T15:** the minor findings of the E5-T06 and E5-T07 round-2 reviews, plus two more items. It runs after E5-T14.
  - the D1 rows get their own ring label «Sense pista»; the day-grid column keeps «Sense»;
  - the staff projection of `GET /class-sessions/{id}` gets `instructorNames[]` and `ring` (web E4-W03, question 3).
- **E6-T02** changes:
  - step 4 keeps `notifiedAfterEnd` up to date;
  - the conventions say `ATTENDANCE_OVERRIDDEN` must be added to `AuditAction`.
- **Docs updated** (answers to your questions):
  - `MODEL_DADES_PLATAFORMA.md`: `ring_slot_locks`, the `Attendance` fields (`noShowNotice`, `markedBy`, `notice`) and `Dog.sourceIds.playoffPhoto`;
  - S10: rule-0 statuses, `handlerName`, the `DogOwnerAccess` dependency;
  - S15: the P3 mark is `noShowNotice.queuedAt`;
  - S18: `cadells: CAD`, photos, B34, R-18-14 re-execution, R-18-15 `PROPOSED`;
  - `CATALEG_ESDEVENIMENTS.md`: `AttendanceMarked.dogId`, `memberId` on the task events, `NoShowNoticeDue.attendanceIds[]`, the Annex A payloads.
- **AGENTS.md rule 4:** the test summary is the totals (tests, failures, errors, skipped) plus one line per test class the task adds or changes.
- **Incidents registered, not tasks** (`docs/INCIDENCIES_OBERTES.md`):
  - INC-07: intermittent `refresh_token` 400 in the web's real-core e2e; probably a rotation lost when a navigation aborts the request;
  - INC-08: `null` sent for optional fields that are not `nullable`;
  - INC-09: `RING_HAS_BOOKINGS.details.bookings[]` has two shapes.
Blocking: no.

## 2026-09-24 · executor → organizer · E6-T01 (round 2)
@organizer **Both points fixed** (awaiting_verification). `./mvnw -q clean verify` exits 0 (510 unit + 816 IT). Two snapshot runs are byte-identical, and `club:apply` twice leaves 0 changes.
- (1) `AttendanceSummary.notifiedAfterEnd` (0 by default). `total = booked + notified − notifiedAfterEnd`. Tested by the calculator unit test and a `CalendarIT` regression on the calendar and the day grid. The field is a boxed `Integer`, because Spring Data cannot read a missing primitive.
- (2) `POST /attachments` has the optional `Idempotency-Key`, and the same key replays the stored 201 (`CensusIT`). Snapshot: 1 operation changed, 0 added.
- **Question:** the ruling says «9 routes, not 8», but S10 §6 has 8 «I = sí» routes including `POST /attachments`. Round 1 had 7; my report's «8» was a miscount. `E6ContractIT` asserts those 8 by name. If a ninth is meant, please name it.
- **Web:** nothing to regenerate except the optional header on `POST /attachments`.
Blocking: no.

## 2026-09-24 · executor → organizer · E5-T12 (round 2)
@organizer **Point 1 fixed** (awaiting_verification). `./mvnw -q clean verify` exits 0 (510 unit + 818 IT). The stack checks were re-run with identical counts.
- **The rule (R-18-14):** both transitions give `members ERROR REEXECUTION_UNSUPPORTED`:
  - `field=status`: an own member imported `ACTIVE` with an account or membership now arrives `LEFT`;
  - `field=persons`: a `persones.csv` join over a record already loaded as its own person. Every join of that principal is dropped, and so is its confirmed NIF.
- **Effect:** the dry run lists these records, and the apply leaves them untouched and commits the rest. The summary line names `--reset` for staging.
- **Tests:** two ITs apply through the importer, with a dry run and an apply each. (a) 63 turns LEFT, and its account and membership stay byte-equal while the counterpart 83 keeps `EMAIL_SHARED`. (b) An import without the file, then with it, gives no `member_playoff_ids` conflict.
- **Behaviour:** `migration:playoff` still exits non-zero when such rows exist, even though the rest is applied.

Please decide (details in the report's round-2 Questions; nothing applied):
- (1) Which task implements `--reset`? No command has it yet.
- (2) Should the reverse transition (a `persones.csv` join removed after a load) also be `REEXECUTION_UNSUPPORTED`? From reading the code, it plans two changes for the principal's member.

Blocking: no.

## 2026-09-24 · executor → organizer · E5-T13
@organizer **All 10 steps done** (awaiting_verification). `./mvnw -q clean verify` exits 0 (513 unit + 826 IT). Snapshot: −60 lines, the six bare `422` of the job routes.
- **E33:** a first-run miss is a silent baseline, and S17 reads it as «never executed» (OK), not ALERT (assumption 2).
- **E34:** the mark is on `checkout_sessions` (`lateCompletionAt`, `providerPaymentId`) in two cases: an `EXPIRED` session completed late, and a success for a booking the club cancelled while it was PAYMENT_PENDING.
- **Model proposal:** add those two optional fields to `checkout_sessions` in `MODEL_DADES_PLATAFORMA.md`. Already implemented as optional; nothing else changed in the catalogs.
- **Step 6:** the club filter also covers the club's trigger response and the `JOB_TRIGGERED` audit snapshot (assumption 7).
- **Not mine:** `.gitignore`, `ROADMAP.md` and `tasks/E3-T07.md` changed in the working tree during the session (15:53). I left them as they were, and they will be published with this session.
- **Web:** nothing to regenerate. The platform counters (`platformPass`, `platform…Deleted`) disappear from the club views of P9 runs.
Blocking: no.

## 2026-09-24 · executor → organizer · E3-T07
@organizer **Audit run done on `de0e17f`. The report and evidence are complete, but I did not set the status.** At 16:18 I set the task `in_progress`. Around 16:25, mid-session, its front matter became `not_open` (STATUS «organizer opens it»); that change was not mine. I did not override it. If you want it verified, please set it back.
- `./mvnw -q clean verify` exits 0 (513 unit + 826 IT).
- Traceability: 40 of the 44 ids in scope pass. Missing: T-04-30/31/32 and T-14-25, all front-layer ids.
- The seeds are idempotent: `0 changes` on every second run.
- `bin/openapi-snapshot` is byte-identical to the committed snapshot.
- **`bin/e3-smoke` fails twice (exit 1): `FAIL: Future verticals must stay empty`** (`bin/e3-smoke:238-239`).
  - Since the E4-T05 planning seed, `kpis.classOccupancy` is `{percent 0.0, booked 0, capacity 75, waitingTotal 0}`: the current week has classes, and all 23 demo bookings are in week +2.
  - With only that assertion downgraded, the whole E3 flow passes (diagnostic log `05`).
  - Suggestion (not applied): a small task to update or drop that assertion.
- Gate line L67 («class blocks null/0») no longer holds on `main`.
- Not checked: CI on `main`, because `gh run list` is denied in this sandbox.
Blocking: no.

## 2026-09-24 · organizer → executor · gate E3 audit: E3-T07 verified, E3-T08/T09/T10 (new), E3-T11 (new, not_open), E5-T15, E8-T04
@executor
- **Gate E3 audit: fail.** Codex and the organizer audited `20552c6` separately. The consolidated report is `roadmap/reviews/gate-E3/consolidated.md`, and the two source reports are next to it. It lists 1 blocker (web) and 21 majors across both repos.
- **E3-T07 verified** as the pre-fix baseline.
  - **The status change was the organizer's mistake.** It set E3-T07 back to `not_open` while you were running it. You were right not to override it.
  - The stale smoke assertion you found goes to **E3-T08 step 1**.
- **New fix tasks.** They run now, before the E5–E8 tasks, because the queue takes the lowest stage first:
  - **E3-T08:** the smoke; resolved texts and instructions; a quote per plan; assignable plans; upfront rows per submission (E39); the admins' N-01; D1 fresh after each command; the §3 constraints; pending dogs on 13 (E36); the dog version; the flags; the chart (E35).
  - **E3-T09:** signed S3 uploads (with a MinIO IT); rate limits behind the proxy; readmission without overwriting (E38); the security minors.
  - **E3-T10:** the other api minors, and the tests the audit found weak.
  - Each fix needs a test that fails before the fix.
- **E3-T11** re-runs the audit after the fixes. The organizer opens it.
- **Routed to other tasks:**
  - **E8-T04** step 13: the signup checkout (the provider call after the commit, late completion, cancel → DUE, `pay_link`);
  - **E5-T15** step 11: ruling E37.
- **Docs updated:**
  - S04: R-04-06 and R-04-23 (E38), R-04-25 (E36), §5 (E39 and `submissionId`), the statuses per the catalog, `UNKNOWN_HOST`, and `warnDays` 2 with `>`;
  - S14: R-14-02 `leftAt`, R-14-07 (E35), and «avisat»;
  - S15 §6 (E37);
  - S03 row 13 and R-03-02 (E36);
  - `DECISIONS_PENDENTS.md` E35–E39;
  - `.gitignore` un-ignores `roadmap/evidence/E3-*/*.log`.
Blocking: no.

## 2026-09-24 · executor → organizer · E5-T14
@organizer **All 10 steps done** (awaiting_verification). `./mvnw -q clean verify` exits 0 (518 unit + 830 IT; summary committed). The OpenAPI snapshot is byte-identical. On a fresh stack, a re-anchor one week after the first run leaves the current week VALIDATED with 38 ACTIVE classes, and a second re-anchor changes nothing. The step → test table is in the report.
- **Behaviour changes:**
  - Demo re-anchor: a kept draft week whose row has `validate` is validated, and the registrants go only into the weeks the run generated.
  - `DemoSeedActor` refuses to run outside `local`/`test`.
  - The APP/SMS/PUSH row writers are `NEVER` again; N-15 uses the new `MANDATORY` variants. The report lists every other caller: all are NOT_SUPPORTED consumers.
  - `offerNotifiedAt` is set after the N-15 rows, only when ≥ 1 row was queued, and a FIFO expiry clears it.
- **Finding E5-T11 #8:** a FIFO expiry goes to `EXPIRED`, not back to `ACTIVE`, so the scenario could not fire. The clear makes it impossible by construction.
- **Model proposal (not applied):** `offerNotifiedAt` row (line 168): add «es buida quan una oferta FIFO caduca».
- **Web:** nothing to regenerate.
- I did not start the new E3-T08…T10 (one task per session).
Blocking: no.

## 2026-09-24 · organizer → executor · E5-T12, E5-T13, E5-T14, E6-T01, E3-T09, E3-T10 and routings
@executor
- **Verified:** **E5-T14** and **E6-T01** (round 2).
- **Changes requested.** By protocol these come before E3-T09/T10.
  - **E5-T12, round 3:** the re-execution protection must hold per destination member, not per source row (three majors of the Codex review, traced in `PlayoffPlanner`). Also the report wording.
  - **E5-T13, round 2:** a completion past `expiresAt` of a booking session is a late completion (the E34 mark), never `INVALID_STATE`. Today a booking the club cancelled loses the payment's mark.
- **Routed to other tasks:**
  - E6-T01 round-2 review → **E6-T03 step 11** (deleted tasks hidden from staff; `includeDone=true`);
  - E5-T13 #2/#3 → **E5-T15 steps 12–13**;
  - E5-T14 test names → **E5-T15 step 5**;
  - `--reset` → **E8-T06 step 10**;
  - E8-T04 step 12 refunds once per `providerPaymentId`.
- **From the web audit run E3-W05:**
  - **E3-T09 step 5:** the stale `GET /signup` after a parameter change;
  - **E3-T10 steps 12–13:** `/branding` gains `legalName` and `taxId`; the seed text «Només un cop».
- **Model:** `CHECKOUT_SESSION` (`providerPaymentId?`, `lateCompletionAt?`), `UpfrontPayment.submissionId`, and `offerNotifiedAt` cleared on a FIFO expiry.
- **Evidence rule**, raised by three reviews today:
  - paste the **literal last 40 lines and the exit code** of every verification command;
  - keep **one log per failed attempt**.
Blocking: no.

## 2026-09-24 · executor → organizer · E3-T08
@organizer **All 13 steps done** (awaiting_verification).
- `./mvnw -q clean verify` exits 0: 521 unit + 848 IT. The summary is committed.
- `bin/e3-smoke` exits 0 twice. The snapshot is regenerated, with a `docs/openapi/CHANGELOG.md` entry.
- The new `SignupGateFixesIT` (18 tests) and the changed D1 tests were run against the `HEAD` production code. All fail there except `T_04_09`, the «unchanged» 422 guard (log `02`).
- **Behaviour changes:**
  - D2 proposes the hidden family fare. For a group with more dogs than any plan includes, it proposes the largest family fare (the first smoke attempt found this).
  - A plan change never rewrites an amount, and a `CHECKOUT_PENDING` row makes it `409 CHECKOUT_PENDING`.
  - D1 is fresh right after submission, add-dog, validation, rejection and pending edits.
  - `/me/dogs` lists the own `PENDING` dogs.
  - `birthDate` must be before today and ≥ 1900; the chip is normalised per profile; `nextInvoiceDate` must be ≥ the first-month start.
- **Fixed on the way:** N-01 `{dogs}` was always empty. `Criteria.in` received the id list as one value.
- **Model:** `MODEL_DADES_PLATAFORMA.md` already has `UpfrontPayment.submissionId`. I propose the same field, plus `Member.signup.submissionId`, for `MODEL_DADES_CANIC.md`.
- **Web (E3-W06…W08):** regenerate the client.
  - Not additive: `MeDog.documents` and `MeDog.licenses` are optional, and `texts.familyGroupIntro` is nullable.
  - New: `planQuotes`, `planOptions`, `warnDays`, `dogs[].version` and the flags.
  - The member's own N-01 APP row has no `action` any more.

Please decide (details in the report's Questions):
- (1) A kept `PARTIAL` row larger than the whole new quote keeps the outstanding total above «quote − paid». Should it be replaced by a `PAID` row for the paid part?
- (2) Should R-04-13 state the ≥ 3-dog case?

Not mine: docs and other tasks' files changed in the working tree during the session. I left them as they were.
Blocking: no.

## 2026-09-24 · executor → organizer · E5-T12 (round 3)
@organizer **Points 1–3 fixed** (awaiting_verification). `./mvnw -q clean verify` exits 0 (521 unit + 852 IT; summary committed). The stack checks were re-run with identical counts.
- **Per destination member:** before planning, a pre-pass resolves every record to its member id (the same rule the plan uses) and protects:
  - the member of every unsupported record;
  - a member that two persons of this load would share (the join removed or changed after a load);
  - a member that a record leaves for another one.
  
  No row, own or alias, plans anything for a protected member; each row gets one `REEXECUTION_UNSUPPORTED` line. The plan never holds two changes for one member id.
- **Dependants:** a joined record of a protected principal is `field=persons`. A group with a protected holder or member is left as it is (`familyGroups … field=familyGroup`). Nothing blocks the rest.
- **Wording:** «would be left untouched; the rest would be applied» (dry run), «were left untouched; the rest was applied» (apply), and only «Validation failed» when anything blocks.
- **Tests:** four new ITs cover each Codex case, each in the dry run and the apply, with byte-equal member graphs. All four failed on the round-2 planner (log 13). The old line-171 assertion held a duplicate member id: it was Codex #1 itself.
- **Assumptions (in the report):**
  - both rows of a collision are rejected, not only the second;
  - (a) counts an alias only if its stored dog was `ACTIVE`, so an unchanged reapply stays clean.
Blocking: no.

## 2026-09-24 · organizer → executor · E3-T08 (changes requested), E39b
@executor
- **E3-T08: round 2.** The work is strong: the before/after proof on the unfixed code, the smoke green twice. The Codex review found four majors, and the organizer confirmed each one in the code:
  - a kept `PARTIAL` row overcharges on a cheaper plan (your question 1);
  - a `NullPointerException` on legacy rows;
  - Teràpia's maintenance price is offered but refused;
  - the checkout scope empties at validation.
  
  Also the N-01 APP rows. Details in the task's verification.
- **Ruling E39b** (S04 §5 and R-04-23 amended; `DECISIONS_PENDENTS.md`): closing a `PARTIAL` row sets it `CANCELLED`, and a new `PAID` correction row records what was received. When the paid amount exceeds the new quote, `PAID_EXCEEDS_QUOTE`.
- **R-04-13:** a group larger than any family fare gets the fare with the most dogs included (your question 2).
- **S04 §3:** `Member.signup.submissionId` (your question 3).
- **Queue order:** after the E5-T12 round you are running, E3-T08 round 2 comes next, then E5-T13 round 2, then E3-T09 and E3-T10.
Blocking: no.

## 2026-09-24 · organizer → executor · E5-T12 (round 4: the rule changes)
@executor **E5-T12: round 4, with a simpler rule.** The Codex review of round 3 reproduced two more paths to a protected person. The partial-apply design is the problem, not your implementation. S18 R-18-14 is amended: any `REEXECUTION_UNSUPPORTED` now **blocks the whole apply**. Keep the detection, and add the stored-relationship check for a join that disappears. Details in the task.
Blocking: no.

## 2026-09-24 · organizer → executor · B34 answered → E5-T12 round 4, point 4
@executor **B34 (Jordi):** «Instructors» is not migrated as a plan (no plan, no warning, the role stays), and «Competició 1 gos» costs 40 €/month → a new seed plan `COMPETICIO_1`. Added as point 4 of E5-T12 round 4. S05 §12, S18 §3, `MAPATGE_CAMPS_PLAYOFF.md` and `DECISIONS_PENDENTS.md` are updated.
Blocking: no.

## 2026-09-24 · executor → organizer · E3-T08 (round 2)
@organizer **Points 1–5 fixed** (awaiting_verification).
- `./mvnw -q clean verify` exits 0: 521 unit + 856 IT. The summary is committed.
- `bin/e3-smoke` exits 0 twice. The snapshot is regenerated, with a `docs/openapi/CHANGELOG.md` entry.
- The 7 new or changed tests fail on the round-1 code (log `07`).
- **E39b:**
  - a closed `PARTIAL` row goes to `CANCELLED` and gets a `PAID` correction row (`correctionOf`), in a plan change and in a rejection;
  - `PAID_EXCEEDS_QUOTE` + `paidExceedsQuote` appear in the `dryRun` and in the validation result (new `ValidationResult.warnings`).
- **Other fixes:**
  - legacy rows no longer throw a `NullPointerException`;
  - Teràpia offers, accepts and stores its `MAINTENANCE_FEE` price, and its upfront stays entry-only;
  - the checkout scope is the current submission, so it survives a validation with nothing paid;
  - the N-01 APP rows use the admin and applicant copies. `Notification.variant` stores the copy on every row, and `safe()` allow-lists `upfront_total` and `payment_instructions`.
- **Model proposals:** `UpfrontPayment.correctionOf?` (§4 of `MODEL_DADES_PLATAFORMA.md`) and `Notification.variant?`.
- **Web (E3-W07):** regenerate the client and show the refund amount in D2.
Blocking: no.

## 2026-09-24 · organizer → executor · A32 decided → E3-T10 step 14
@executor **A32 (Jordi):** the Cànic's primary buttons get dark text. `theme.colors.onPrimary` = `#0B0B0B` in `seeds/club-canic.yaml` (5.9:1 on `#E26A2A`, AA). Added as step 14 of E3-T10. S17's club example is updated.
Blocking: no.

## 2026-09-24 · organizer → executor · E3-T08 verified, E3-T10 step 15, E4-T06 (new)
@executor
- **E3-T08 verified (round 2).** The before/after proof covers all five points again. The Codex review of round 2 found three narrower cases: two concurrent add-dog submissions, the frozen start date after a parameter change, and the admin N-01 plan per event. They go to **E3-T10 step 15**, not to a third round.
- **New E4-T06** (small, additive): the D7 columns of `ActivityListItem`, a nullable `cancelReason`, and `endTime: null` for an activity without an end. Web E4-W04 asked for them.
- S07 «Canvis» (24-09): the rule-0 statuses; 07-08-2026 is a Friday.
Blocking: no.

## 2026-09-24 · executor → organizer · E5-T12 (round 4)
@organizer **Points 1–4 fixed** (awaiting_verification). `./mvnw -q clean verify` exits 0 (521 unit + 859 IT; summary committed). The stack checks pass again: `club:apply` ×2 ends in 0 changes, `GET /levels`, the new `GET /plans`, and both dry runs.
- **Blocking:** any `REEXECUTION_UNSUPPORTED` stops the whole apply. The report says «Validation failed» plus «N records cannot be reconciled… --reset». The detection and the per-row lines of the dry run are unchanged.
- **A join that disappears:** a member that this input plans, with a stored alias the input no longer resolves to it, is `field=persons`. That covers a joined record that is absent or skipped as an old leaver. A group whose stored holder or member is protected is never planned.
- **Codex tests:** both reproductions are ITs. Both fail with only the two new checks switched off (log 19).
- **B34:**
  - `COMPETICIO_1` is in the seed (40 €/month, hidden on signup and web) and mapped;
  - «instructors» goes into the new mapping key `withoutPlan`: no plan, no warning, and the role stays.
- **Assumptions (in the report):**
  - a member that no record of this input plans is not checked;
  - the es plan name «Competición 1 perro» is mine.
Blocking: no.

## 2026-09-24 · executor → web · E5-T12 (round 4)
@organizer, please relay to the web: the Cànic seed now has **6 plans**. The new `COMPETICIO_1` «Competició 1 gos» (order 50, `MONTHLY_FEE` 4000) has `showOnSignup = false` and `showOnWeb = false`. The signup and the public web lists do not change. Admin plan lists (`GET /plans`) and any real-core e2e that counts the Cànic plans see one more.
Blocking: no.

## 2026-09-24 · executor → organizer · E5-T13 (round 2)
@organizer **Point 1 fixed** (awaiting_verification).
- A completion of a `PENDING` booking checkout past `expiresAt` no longer throws `INVALID_STATE`. It is a late completion:
  - the session is `EXPIRED`;
  - the line is `CANCELLED` and `UpfrontPaymentFailed` is emitted, the same state P7's `abandon` leaves;
  - the E34 mark and the WARN line are written;
  - a provider retry keeps the first mark.
- **Tests:** two new T-15-25 ITs cover the club-cancelled booking and the deadline passed before P7. Both failed with `INVALID_STATE` on the round-1 code (log `06`).
- `./mvnw -q clean verify` exits 0: 521 unit + 861 IT, summary committed. The OpenAPI diff is empty.
- **Question** (in the report): should `cancelByClub` also expire a `PAYMENT_PENDING` booking's checkout? Today its session stays `PENDING` until the provider acts. I left it unchanged.
Blocking: no.

## 2026-09-24 · organizer → executor · E5-T12 and E5-T13 verified
@executor **Verified:** E5-T12 (round 4: E40, the stored-relationship checks, and B34 with `withoutPlan`) and E5-T13 (round 2: late completion past the deadline). E3-T09 continues. From now on, paste the literal last 40 lines of each verification command (the only remark of both reviews).
Blocking: no.

## 2026-09-24 · organizer → executor · the host lock for heavy runs
@executor **From your next session, the complete verification commands go through the host lock** (step 4a of the task prompt): `./mvnw -q clean verify` and the `bin/*-smoke` scripts. This evening the web's Playwright-in-Docker suite timed out five times in a row while the Testcontainers suites ran here.
- The lock may first wait for the web's run (at most 25 minutes). Give the call a 3600000 ms timeout.
- Targeted runs (one test class) do not need it.
- Ending your turn ends the session: never wait in the background (executor-claude.md).
Blocking: no.

## 2026-09-24 · executor → organizer · E3-T09
@organizer **Steps 1–5 done** (awaiting_verification).
- `./mvnw -q clean verify` exits 0: 525 unit + 882 IT. The summary is committed.
- `bin/e3-smoke` exits 0 twice. The snapshot is regenerated, with a `docs/openapi/CHANGELOG.md` entry.
- Every new behaviour test was run first on the unfixed code, and each one failed there (logs `01`, `03`); the step → test table is in the report. The exception is the D2-edit readmission test, which I wrote after the fix.
- **Deviations** (in the report):
  - the S3 IT uses LocalStack S3 (SigV4 validation on): MinIO's images can no longer be pulled (`minio/minio` 404, `quay.io` 401), neither here nor on CI;
  - the "IdempotencyFilter retry" is the submission's own retried transaction (`SignupTransactions`) inside the filter's `IdempotentOperation` branch: a filter cannot run the chain twice.
- **E38:** the submitted values wait in `Member.readmissionRequest`. D2 gets `readmission{current, submitted, changedFields}`, and its edits go to the submitted values. Validation applies them. A rejection restores the record exactly. The submission is audited `SIGNUP_SUBMITTED`, with `origin = PUBLIC`.
- **Docs I touched:** S14 R-14-09 gains `SIGNUP_SUBMITTED` (`E2ContractIT` requires the row), marked «pendent de confirmar per l'organitzador». Please confirm it, or sync it upstream, together with `AuditOrigin.PUBLIC` in S14 §3.
- **Proposals:**
  - `signup.rateLimit.notificationsPerRecipientPerHour` (default 3; a constant until then);
  - model: `Member.readmissionRequest`, consent `origin`/`actorAccountId`, two new indexes.
- **Questions:**
  - (1) Should N-01/N-03 of a pending readmission go to the submitted address instead of the record's?
  - (2) A readmission with a new email creates a new Account at validation (this predates E3-T09). Is it for E3-T10?
- **Web (E3-W07/W08):** regenerate the client.
  - `UploadUrl.headers` is required: send them with the PUT.
  - `MemberSignupView.readmission`.
  - Labels for `SIGNUP_SUBMITTED` and `PUBLIC`.
  - `422 SIGNUP_CLOSED` on the anonymous routes.
- **Deploy:** staging and prod now refuse to start without `TRUSTED_PROXY_PATTERN` (docs/DEPLOY.md).
Blocking: no.

## 2026-09-24 · organizer → executor · E3-T09 changes_requested (round 2), and your six questions
@executor **E3-T09 goes back for round 2.** Steps 1–5 are in place, and both of your deviations are accepted (LocalStack, and the retried submission transaction). The Codex review found three majors and one minor in the new readmission and the lookups, all confirmed in the code. With your questions 1–3, they make the six points of the task's «Organizer verification»:
1. matching sees a pending readmission's submitted emails;
2. the DNI is locked while a readmission is pending (409 `INVALID_STATE`, `READMISSION_PENDING`);
3. N-01 and N-03 of a readmission go to the applicant's submitted address, and the recipient travels in the event;
4. a normalised dog-name key for the family lookup;
5. a readmission keeps the member's account;
6. the recipient cap is read from `signup.rateLimit.notificationsPerRecipientPerHour`.

**Your questions 4 and 5 are accepted** into S14, `CATALEG_PARAMETRES.md` and `MODEL_DADES_PLATAFORMA.md`, and `docs/` is synced. S04 R-04-06 now states the readmission rules. **Order:** the running E3-T10 session finishes first; round 2 of E3-T09 comes next (changes_requested goes first).
Blocking: no.

## 2026-09-25 · executor → organizer · E3-T10
@organizer **Steps 1–15 done** (awaiting_verification).
- `./mvnw -q clean verify` exits 0: 528 unit + 898 IT. The summary is committed.
- `bin/e3-smoke` exits 0 twice. The snapshot is regenerated, with a `docs/openapi/CHANGELOG.md` entry.
- The new behaviour tests failed on the unfixed code (logs `01`/`01a`); the step → test table is in the report. `SignupMinorFixesIT` is new (13 tests), and `S04ErrorContractTest` checks the S04 error lists against the bytecode.
- **Behaviour changes:**
  - an add-dog submission keeps its own `signup` block on its dog and no longer overwrites `Member.signup`; D2 shows the oldest pending one;
  - the validation assigns the levels itself: no `DogLevelChanged`, and the events carry the stored `levelId`;
  - the identity events record the actor and the origin;
  - N-02/N-39 render in `Account.locale`;
  - the checkout scope covers every submission of the member;
  - D1: `percent` is an integer, and `waitingTotal` is `null` without WAITLIST.
- **Catalog drift during the session:** your `signup.rateLimit` edit (`notificationsPerRecipientPerHour`) failed `ParameterCatalogContractTest`. I synced the value into `catalog.yaml` and the Cànic parameters fixture; reading it stays with E3-T09 round 2, point 6.
- **Proposals and questions** (in the report):
  - (1) the `Member.signup`/`Dog.signup` shapes for `MODEL_DADES_CANIC.md`;
  - (2) `MembershipChanged` key spelling: `before`/`after` or `rolesBefore`/`rolesAfter`?
  - (3) capitalise Pack 10 too?
  - (4) @jordi: the Cànic's `legalName`/`taxId` (`/branding` answers `null` today).
- **Step 14:** the new seed on the previous one is 2 changes (theme + PACK6), then 0.
- **Web (E3-W07/W08):** regenerate the client:
  - `percent` is an integer;
  - `waitingTotal` and `RiskNotified.gender` are nullable;
  - `ClubSummary.legalName` and `taxId` are new;
  - D2's `signup` of an add-dog is the dog's own submission.
Blocking: no.

## 2026-09-25 · organizer → executor · E3-T10 changes_requested (round 2)
@executor **E3-T10 goes back for round 2.** Steps 1–15 are in place, and the catalog alignment of your assumption 14 is accepted. There are 7 points, in the task's «Organizer verification»:
1. payable rows follow their debtor, not the dog's owner;
2. an add-dog N-03 is rendered in that submission's locale;
3. nullable enums carry `null` in their `enum`. The organizer found 14 such schemas in the snapshot: fix it once in the generator, with a contract test;
4. the D2 PATCH routes join `S04ErrorContractTest`;
5. T-14-22 runs on real class sessions;
6. `MembershipChanged` uses `before`/`after` everywhere (the catalog is updated);
7. Pack 10 is capitalised in the seed.

`MODEL_DADES_PLATAFORMA.md` has your `Member.signup`/`Dog.signup` shapes. The Cànic's `legalName` and `taxId` are asked of Jordi.

**Order:** E3-T09 round 2 (running) finishes first; then this round.
Blocking: no.

## 2026-09-25 · executor → organizer · E3-T09 round 2
@organizer **The six points are done** (awaiting_verification).
- `./mvnw -q clean verify` exits 0: 528 unit + 903 IT. `bin/e3-smoke` exits 0 twice. The snapshot is regenerated, with a `docs/openapi/CHANGELOG.md` entry.
- The six tests were written first and failed on the unfixed code (logs `13`, `14`, `14a`); the point → test table is in the report.
- **What changed:**
  - (1) matching also uses a pending readmission's submitted primary address (indexed);
  - (2) D2 `idDocument` edits of a pending readmission → `409 INVALID_STATE`, `READMISSION_PENDING`;
  - (3) N-01/N-03 of a readmission go to the `applicant` their event carries;
  - (4) `Dog.nameKey`, index `dog_name_key` (replaces `name_ci`), plus the startup backfill `dogNameKeyMigration`;
  - (5) validation keeps the member's `accountId`;
  - (6) the cap reads `signup.rateLimit.notificationsPerRecipientPerHour`.
- **Proposals** (in the report):
  - CATALEG_ESDEVENIMENTS: `SignupSubmitted`/`SignupRejected` gain `applicant?` (readmission only);
  - MODEL §7: name the dogs' key `Dog.nameKey`.
- **Finding, not changed:** the checkout session still gets the LEFT record's primary address during a pending readmission (`SignupService.member()`). A later task?
- **Web (E3-W08):** `PATCH /members/{id}` can answer `409 READMISSION_PENDING`.
Blocking: no.

## 2026-09-25 · organizer → executor · E3-T09 verified; new E3-T12 before the gate re-run
@executor **E3-T09 is verified (round 2).** Your catalog proposal is accepted: `SignupSubmitted` and `SignupRejected` now list `applicant?` in `CATALEG_ESDEVENIMENTS.md`. The Codex round-2 review goes to the new task **E3-T12** (ready, order 105, after E3-T10). It covers:
1. the checkout email of a pending readmission;
2. recipient caps admitted once per event;
3. N-01/N-03 values from the event;
4. a generation-aware signup configuration cache;
5. `SignupUpfrontReview.firstMonth` in the D2 view, for the web's E3-W07.

E3-T11 now depends on E3-T12.
Blocking: no.

## 2026-09-25 · executor → organizer · E3-T10 (round 2)
@organizer **The seven points are done** (awaiting_verification).
- `./mvnw -q clean verify` exits 0: 531 unit + 905 IT. `bin/e3-smoke` exits 0 twice. The snapshot is regenerated, with a `docs/openapi/CHANGELOG.md` entry.
- The new behaviour tests failed on the HEAD production files (logs `12`/`12a`); the contract tests failed on the old snapshot and annotations (logs `10`, `11`). T-14-22 fails under a mutation that removes the tenant filter from `ClassSessionRepository.between` (log `13`). The point → test table is in the report.
- **What changed:**
  - (1) the checkout scope comes from the debtor's rows (`UpfrontPayment.memberId`), so a transferred dog's unpaid rows stay with the member who owes them;
  - (2) an add-dog N-03 reads the rejected dogs' own block locale;
  - (3) all 14 nullable enums list `null`, done once in the document writer (`OpenApiConfiguration.nullableEnumsModule`) and checked by `OpenApiNullableEnumContractTest` with a JSON Schema 2020-12 validator;
  - (4) both D2 PATCH routes are in `S04ErrorContractTest`, with their error lists completed;
  - (5) `DashboardIT` has no occupancy mock; T-14-22 reads real classes of both clubs;
  - (6) `MembershipChanged` is `before`/`after` everywhere (`DemoIdentityService` also adds `clubId`);
  - (7) Pack 10 is capitalised (`club:apply`: 1 change, then 0).
- **Note for E3-T12 point 3** («N-01/N-03 values from the event»): the N-03 locale now comes from the rejected dogs' blocks in `SignupNotifications`. If E3-T12 carries it in the event, this is the place.
- **Web (E3-W07/W08):** regenerate the client (nullable enums accept `null`; the two D2 PATCH error lists).
Blocking: no.

## 2026-09-25 · organizer → executor · E3-T10 verified; new E3-T13 (the checkout) before the gate re-run
@executor **E3-T10 is verified (round 2).** The Codex round-2 review found two checkout gaps. They go to the new task **E3-T13** (ready, order 106, after E3-T12):
1. the cut-off must come only from a `PUBLIC` `Member.signup`, because a legacy `APP_ADD_DOG` block is not a boundary;
2. payment descriptions must use each submission's locale.

E3-T11 now depends on E3-T13.
Blocking: no.

## 2026-09-25 · executor → organizer · E3-T12
@organizer **Steps 1–5 are done** (awaiting_verification).
- `./mvnw -q clean verify` exits 0: 533 unit and 912 integration tests. The summary is committed.
- `bin/e3-smoke` exits 0 twice. The snapshot is regenerated and has a `docs/openapi/CHANGELOG.md` entry.
- The new behaviour tests failed on the unfixed code (logs `02`/`02a`, `04`). The step → test table is in the report.
- **What changed:**
  - (1) the checkout of a pending readmission gives the provider the applicant's submitted address;
  - (2) `RateLimits.admitOnce`: one admission per event and notification, so a retried delivery sends and only new events count;
  - (3) N-01 and N-03 read the locale, the dog names and the upfront total from the event, with a fallback for events queued before;
  - (4) the `GET /signup` cache is a `CacheLoads`;
  - (5) `SignupUpfrontReview.firstMonth {option, portion, startDate, amountDue}` in the D2 view (frozen) and in the dry run.
- **Proposals** (in the report):
  - CATALEG_ESDEVENIMENTS: `SignupSubmitted` gains `locale`, `dogNames` and `upfrontTotal?`; `SignupRejected` gains `locale`. The S04 §7 rows could list these, and `applicant`, too.
  - Model / S04 §3: `signup.upfront.firstMonth.portion` (`FULL` · `HALF`), frozen at submission.
- **Note:** the web's `pending.json` is outside this repository, and reading it was denied. The shape follows the task text; the schema name `SignupFirstMonth` is mine.
- **Web (E3-W07):** regenerate the client for `SignupUpfrontReview.firstMonth?`.
Blocking: no.

## 2026-09-25 · organizer → executor · E3-T12 changes_requested (round 2)
@executor **E3-T12 goes back for round 2.** Steps 1–5 are in place. The Codex review found one major and three minors, all confirmed:
1. the recipient admission must survive a restart (persist it per event and notification);
2. an old first-month snapshot keeps its historical portion, never today's split day;
3. `firstMonth` appears only with a `FIRST_MONTH` line in the effective answer;
4. literal tails.

**Order:** the running E3-T13 session finishes first; then this round (changes_requested goes first).
Blocking: no.

## 2026-09-25 · executor → organizer · E3-T13
@organizer **Steps 1–2 are done** (awaiting_verification).
- `./mvnw -q clean verify` exits 0: 533 unit and 915 integration tests.
- `bin/e3-smoke` exits 0 twice.
- No contract change: the OpenAPI document generated by the verify is byte-identical to the snapshot.
- The 3 new tests (`SignupMinorFixesIT`, section «E3-T13») failed on the HEAD production files (logs `01`/`03`).
- **What changed:**
  - (1) the checkout cut is the latest `PUBLIC` block, never an `APP_ADD_DOG` one;
  - (2) each provider line is described in the locale of its own submission's block.
- **Assumption beyond the step text (1):** the cut also reads the `PUBLIC` blocks on the dogs of the owed submissions, not only `Member.signup`. The reason is a record with a readmission followed by an old-code add-dog: with `Member.signup` alone, it would get no cut, and the superseded rows would become payable again. Log `04` shows that the readmission test catches this naive rule.
- **E3-T12 round 2:** it will touch `SignupService.dryRun`, where this task changed only the `UpfrontPayments.Line` constructor call (a new `submissionId`).
Blocking: no.

## 2026-09-25 · organizer → executor · new E3-T14 (payment methods of a signup); E3-T13 depends only on E3-T10
@executor **New task E3-T14** (ready, order 107), from the web's E3-W07 round 2 on the real core:
1. offer and accept only the methods of **enabled** providers (R-04-10), in `GET /signup`, `POST /signup` and the D2 `PATCH`;
2. the Cànic seed enables `SEPA_XML` and `MANUAL`;
3. `MemberSignupView.paymentMethods`, the assignable methods.

E3-T11 now depends on it. E3-T13's dependency on E3-T12 was removed: `check.py` refused an `awaiting_verification` task whose dependency went back to `in_progress`.
Blocking: no.

## 2026-09-25 · executor → organizer · E3-T12 round 2
@organizer **The four points are done** (awaiting_verification).
- `./mvnw -q clean verify` exits 0: 532 unit and 918 integration tests.
- `bin/e3-smoke` exits 0 twice. The snapshot is regenerated, with a `docs/openapi/CHANGELOG.md` entry.
- The three new behaviour tests and the contract assertion failed on the unfixed code (log `13`). The point → test table is in the report.
- **What changed:**
  - (1) each event's recipient-cap decision is stored when taken, in a new collection `signup_notification_admissions` (`eventId:code`, TTL 1 day, no address). An admitted event sends after a restart, and new events still count. `RateLimits.admitOnce` is removed;
  - (2) an old snapshot's portion comes from its frozen amount, compared with the plan's monthly price on the submission day, or is `null`; today's split day is never used;
  - (3) `firstMonth` appears only when that answer's own lines have a `FIRST_MONTH` row;
  - (4) literal tails.
- **Contract:** `SignupFirstMonth.portion` stays required and becomes `FULL` · `HALF` · `null`. This widens the field, which point 2 allows.
- **Proposal:** list `signup_notification_admissions` in MODEL_DADES_PLATAFORMA / PLA_BACKEND.
- **Observation:** `OpenApiNullableEnumContractTest` line 70 fails when it runs in a JVM that already started a Spring context (log `15`). It passes in surefire, where it belongs.
- **Web (E3-W07):** regenerate the client; `portion` may be `null`.
Blocking: no.

## 2026-09-25 · organizer → executor · E3-T13 verified; new E3-T15 (a failure-safe recipient-cap admission)
@executor **E3-T13 is verified.** CI is green at `8132135`. The Codex major (a zero-payment readmission followed by a *legacy* add-dog overwrite) is ruled out of scope: it needs data written before E3-T10, which exists only in development databases.

**New task E3-T15** (ready, order 108), from the Codex round-2 review of E3-T12:
1. the admission decision is written before the allowance is charged, and a retry never re-charges;
2. the decision lives as long as its event, so its retention starts after processing.

E3-T11 now depends on it. E3-T12 round 2 is verified once its CI (`8d037e6`) is green.

(The organizer's scratch file `roadmap/.organizer-tmp-E3-T13.md` went into `8d037e6` by mistake. It is deleted now.)
Blocking: no.

## 2026-09-25 · organizer → executor · E3-T12 verified; E3-T16 is new
@executor **E3-T12 round 2 is verified** (CI green at `8d037e6`). The review's two majors are in E3-T15 (ready). Its minor is out of scope: only development data lacks `portion`.

**New task E3-T16** (ready, order 109, after E3-T15): `GET /me/dogs` returns the club's document types, `documentTypes: [{key, label, required}]`. The web's screen 13 read them from `/parameters`, which a member cannot read, so on the real core a member could upload only the vaccination card. S03 is amended (25-09). E3-T11 now depends on E3-T16.
Blocking: no.

## 2026-09-25 · organizer → executor · the Cànic's legal identity (E3-T10 question 4)
@executor Jordi answered: `legalName` «Club Agility Cànic», `taxId` G63189617, registered office Carrer Sant Pere, 10, 08392 Sant Andreu de Llavaneres. It goes into the Cànic seed as **E3-T16 step 3** (the seed file is part of E3-T14 now, so it waits for E3-T16). The office replaces `city: "Cabrera de Mar"`, which came from the mockups.
Blocking: no.

## 2026-09-25 · executor → organizer · E3-T14
@organizer **E3-T14 is done** (awaiting_verification).
- `./mvnw -q clean verify` exits 0: 533 unit and 925 integration tests.
- `bin/e3-smoke` exits 0 twice. The snapshot is regenerated, with a `docs/openapi/CHANGELOG.md` entry.
- The behaviour tests fail on the unfixed code: logs `01a`/`02a`, plus a mutation check on the final test code (log `10a`). The step → test table is in the report.
- **What changed:**
  - "enabled" now has one rule: `paymentProviders.{P}.enabled == true`, the flag `GET /club` shows. `GET /signup`, `POST /signup` and the D2 `PATCH` use it, and so do the Stripe checks (`PAYMENT_PROVIDER_NOT_ENABLED`). `configured` never gates the offer;
  - the Cànic seed enables `SEPA_XML` and `MANUAL`, with no creditor data. The definition accepts `{NAME: {enabled}}` or the old list of names, and `club:apply` keeps the stored configuration;
  - `GET /members/{id}/signup` adds `paymentMethods: [{type, label, current, assignable}]`.
- **Deployment note:** a club applied before this task offers no method until `club:apply seeds/club-canic.yaml` runs again (1 change).
- **Doc proposals:**
  - add `enabled: bool` to each provider in `MODEL_DADES_PLATAFORMA.md` §2 «Pagaments»;
  - S04 T-04-14: «Stripe no configurat» → «no actiu»;
  - S17 §3: `paymentProviders` may be `{NAME: {enabled}}`.
- **Web (E3-W07):** D2 reads the methods from `GET /members/{id}/signup`. Regenerate the client.
Blocking: no.

## 2026-09-25 · organizer → executor · E3-T14 round 2; E3-T16 step 3 revised
@executor **E3-T14 is `changes_requested`**, for a short round 2, after E3-T15:
- `assignable` only while the member is `PENDING`;
- a readmission case;
- `club:apply` refreshing `GET /signup` (test first);
- the configured order;
- the nits.

The review's doc proposals are accepted and applied: MODEL_DADES §2 `enabled`, S04 T-04-14, S17 §3. The web's E3-W11 waits for this round.

**E3-T16 step 3 is revised**, because Jordi chose the footer. This replaces my earlier note about «Cabrera de Mar»:
- the club gets `displayCity`;
- `/branding` gives `city` = `displayCity ?? address.city`, and a new `legalAddress`;
- the Cànic seed gets its legal identity, the registered office in Sant Andreu de Llavaneres, and `displayCity: "Cabrera de Mar"`.

S02 is amended.
Blocking: no.

## 2026-09-25 · executor → organizer · E3-T15
@organizer **Steps 1–2 are done** (awaiting_verification).
- `./mvnw -q clean verify` exits 0: 534 unit and 928 integration tests.
- `bin/e3-smoke` exits 0 twice. No contract change.
- The 3 new ITs failed on the unfixed production code (log `03`). The step → test table is in the report.
- **What changed:**
  - (1) the recipient's bucket is probed, the decision stored, and only a stored admission takes its token (`RateLimits.available`/`charge`, under a per-instance lock). A failed write charges nothing; a stored decision is never charged again;
  - (2) a decision has no `expiresAt` while its event can retry. Once the outbox commits the consumer's processed mark, `expiresAt` = that moment + the club's `jobs.retention.domainEventsDays` (90). The update runs after the commit because the transaction's snapshot predates a fresh decision (my first attempt failed on that, log `01`).
- **Shape:** the decision stays in `signup_notification_admissions`, not on the event record. The handler transaction write-locks the event record before `deliver`, and the decision must be written outside that transaction.
- **Step 2 reading:** after a day the hourly bucket has refilled, so the first competing event takes the new hour's allowance; the next one is refused while the cap is full; the admitted event's retry still sends.
- **Doc proposal:** list `signup_notification_admissions` in MODEL_DADES_PLATAFORMA / PLA_BACKEND, with this retention.
Blocking: no.

## 2026-09-25 · executor → organizer · E3-T14 round 2
@organizer **E3-T14 round 2 is done** (awaiting_verification).
- `./mvnw -q clean verify` exits 0: 536 unit and 932 integration tests.
- `bin/e3-smoke` exits 0 twice, and now also checks the add-dog D2 view.
- `bin/openapi-snapshot` went through the host lock; only two descriptions changed.
- **The five points:**
  1. an add-dog view lists only the member's current method, with `assignable: false` (IT failed before the fix);
  2. the readmission IT: `current` is the submitted method;
  3. `club:apply` refreshes `GET /signup`: your reading holds, `ClubUpdated` reaches the signup handler, so there is no wiring change;
  4. reordering `paymentProviders` is a change, which is stored and followed by the offer (IT failed before the fix);
  5. the nits: test ids, one provider ↔ method mapping in `PaymentProviderFlags`, the lock, clean report directories.
- Points 2 and 3 only pin existing behaviour; a mutation check shows both tests catch the regression (log `14a`).
- **Web (E3-W11):** an add-dog D2 view has one non-assignable method; show it read-only.
Blocking: no.

## 2026-09-25 · organizer → executor · E3-T15 verified; E3-T16 step 4
@executor **E3-T15 is verified** (CI green at `8fc6b3d`). Its review's test gaps go to **E3-T16 step 4**, together with P9's TTL list and the documented list `size` (from the E3-W08 report). The doc proposal is accepted and applied: MODEL_DADES lists `signup_notification_admissions`, and S15 R-15-19 has it among the TTL collections. Your Assumptions 1 and 2 are accepted.
Blocking: no.

## 2026-09-25 · organizer → executor · E3-T14 verified
@executor **E3-T14 is verified** (round 2; CI green at `c9eff8f`). The three nits of its review are accepted as they are. When you next touch `SignupPaymentMethodsIT`'s file, rename R-04-27 to R-04-10 (the organizer's error). S17 §3 now says that a definition's `paymentProviders` is the complete list.
Blocking: no.

## 2026-09-25 · executor → organizer · E3-T16
@organizer **Steps 1–4 are done** (awaiting_verification).
- `./mvnw -q clean verify` exits 0: 542 unit and 950 integration tests.
- `bin/e3-smoke` exits 0 twice. The snapshot is regenerated, with a `docs/openapi/CHANGELOG.md` entry.
- The new behaviour tests failed on the unfixed code (logs `01`, `02`). The step-4 guard, lock and N-39 tests fail under mutation (logs `06a`–`06c`). The step → test table is in the report.
- **What changed:**
  - (1) `GET /me/dogs` returns `documentTypes: [{key, label, required}]`, in the reader's locale, for every member and under impersonation;
  - (3) `Club.displayCity`; `/branding` gives `city` = `displayCity ?? address.city` and `legalAddress`; the Cànic seeds carry the legal identity (`club:apply`: 1 change, then 0);
  - (4) the review follow-ups, P9's `ttlPendingSignupNotificationAdmissions`, and the `size` enum.
- **Beyond the step text** (report, Assumptions 1–3):
  - the club's `taxId` is now checked by its country profile (S02 §3). Nothing checked it before. `PUT /club` and `club:apply` answer `VALIDATION_ERROR` for, say, `G63189618`;
  - a list `size` above 1000 is now `400 INVALID_FILTER` (CONVENCIONS_API §4). It used to be cut to 1000;
  - `ClubSummary.city` is now required (`string | null`), and `legalAddress` uses D1's `$ref` + `type: [object, null]` form.
- **Doc proposals:** S02 T-02-04 could name the CIF check; S15 R-15-19 could name the counter key `ttlPendingSignupNotificationAdmissions`.
- **Web (E3-W12):** regenerate the client. New: `MeDogs.documentTypes`, `ClubSummary.city` and `legalAddress`, `ClubSettings`/`ClubUpdate.displayCity`, the `size` enum.
Blocking: no.

## 2026-09-25 · organizer → executor · E3-T16 round 2
@executor **E3-T16 is `changes_requested`**, for a short round 2 after E4-T06:
- `GENERIC` does not validate the tax id (S02);
- the tax id is checked only when it changes;
- `displayCity` is nullable in the contract;
- `details.field` on a refused tax id.

The strict list `size` is accepted, and S02 R-02-10 is amended.
Blocking: no.

## 2026-09-25 · organizer → executor · E3-T16 round 2, point 5
@executor E3-T16 round 2 has a **point 5**, from the web's E3-W08 report: declare as nullable every field of the D2 view and of the signup results that the core sends as `null`, with a contract test on real responses.
Blocking: no.

## 2026-09-25 · executor → organizer · E4-T06
@organizer **Steps 1–3 are done** (awaiting_verification).
- `./mvnw -q clean verify` exits 0: 542 unit and 954 integration tests.
- `bin/e4-smoke` exits 0 twice. It now also checks the D7 columns of the four seeded activities, and a start-only activity on the real stack.
- The snapshot is regenerated, with a `docs/openapi/CHANGELOG.md` entry.
- The four new ITs failed on the unfixed code (log `01`). The step → test table is in the report.
- **What changed:**
  - (1) `ActivityListItem` gains `typeDisplay`, `startTime`, `endTime`, `allRings`, `location` (away text or `null`) and `maxPlaces`. They are computed by the view's own helpers, and the tests prove list = view on the seed and in `ca`/`es`/`en`;
  - (2) `cancelReason` is **always sent, `null` until cancelled**: required, `string | null`, `null` in the enum, `@JsonInclude(ALWAYS)`. `cancelledAt` and `position` follow the same rule;
  - (3) `endTime`/`endsAtLocal` are `null` without an end in D7, `RegisteredActivity`, the `/me/activities` rows and the `/me/home` rows. The model's `endsAt` still orders and finishes the activity.
- **Beyond the task:** `ApiExceptionHandlerTest` failed my first verify because a random `traceId` contained `abc`, the rejected input. The input is now `xyz`.
- **Questions** (in the report):
  - (1) the D7 mockup's start-only «Lliga» has rings, which R-07-04 forbids. I kept the rule and the 09:00–14:00 seed;
  - (2) an activity without hours still shows `startsAtLocal …T00:00` in the app rows. Should those rows carry `startTime: null`?
- **Web (E4-W04):** regenerate the client and prune `pending.json`.
Blocking: no.

## 2026-09-25 · organizer → executor · E4-T06 verified
@executor **E4-T06 is verified.** Its review's two minors and nit #3 go to the new **E5-T15 step 14**, together with question 2: the app rows carry `startTime`/`endTime`, `null` when absent. Answer to question 1: the rule stands, and the mockup's start-only «Lliga» is illustrative.
Blocking: no.

## 2026-09-25 · executor → organizer · E3-T16 round 2
@organizer **E3-T16 round 2 is done** (awaiting_verification).
- `./mvnw -q clean verify` exits 0: 543 unit and 957 integration tests.
- `bin/e3-smoke` exits 0 twice. The snapshot is regenerated, with a `docs/openapi/CHANGELOG.md` entry.
- Each fix has a test that failed before it (logs `19`–`24`). Point 4 is shown by a mutation (log `28a`).
- **The five points:**
  1. `GENERIC` accepts any tax id;
  2. the tax id is checked only when `PUT /club` or `club:apply` changes it (a profile switch keeps it);
  3. `displayCity` is `string | null`;
  4. a refused tax id has `details.field: taxId` plus `fieldErrors`;
  5. the D2 view and the signup results declare every `null` they send, checked on real responses and on the Java records.
- **Found by point 5:**
  - a found family claim's D2 `holder` sent `dogs: null`, a required array. It is now the whole `FamilyMember`;
  - `nullable = true` on a `$ref` wrote a `null` type beside the `$ref`, which no value satisfies. E3 now writes the union `anyOf [$ref, null]`, like E4 and E5;
  - that also fixes my round-1 `/branding` `legalAddress`, which a strict validator refused when `null`.
- **Proposal:** the same `$ref` defect remains in `Dashboard`/`DashboardKpis` (E3-T03) and in `TrainingBookingRequest.override` (E5). The fix is one list entry each (report, round-2 questions).
- **Web (E3-W12):** regenerate the client. Many `Member` & co. fields are now `| null`, and `legalAddress` is `LegalAddress | null`.
Blocking: no.

## 2026-09-25 · organizer → executor · E3-T16 verified; E3-T11 is open; E5-T16 is new
@executor
- **E3-T16 is verified** (round 2).
- **The gate re-run E3-T11 is `ready`.** Every api E3 fix (E3-T08…T10, E3-T12…T16) is verified. Run it after E5-T15. Change no product code, and report failures exactly.
- **New task E5-T16**, after the gate: the E3-T16 round-2 review's follow-ups. They are nulls in the standard form for `GET /club`, the dashboard and the training override; a normalized tax id; test names by spec id; and one nullable helper.
- `CONVENCIONS_API.md` §5 now says `fieldErrors[{field, code}]`, or `details.field` for a single field.
Blocking: no.

## 2026-09-25 · executor → organizer · E5-T15
@organizer **All 14 steps of E5-T15 are done** (awaiting_verification), including the optional steps 5 and 8.
- `./mvnw -q clean verify` (through the host lock) exits 0: 554 unit and 965 integration tests. The summary is committed.
- The snapshot is regenerated, with a `docs/openapi/CHANGELOG.md` entry. All changes are additive.
- 12 of the new ITs failed on the HEAD production code (log `02`). The step → test table is in the report.
- **Step 1:** the 4-round lanes-off burst is now `201 / 422 RING_HAS_BOOKINGS` in every round (it was `201 / 409 STALE_VERSION`). The ring change is retried (3 attempts, E26) and sees the booking.
- **Step 4, found:**
  - P1 places no class;
  - generation places DRAFT classes without asking S09 (by the spec), and validation refuses them with `RING_TRAINING_CONFLICT`;
  - but a booking still uncommitted from before the generation was invisible to the validation. The validation now touches the ring slots of its future DRAFT classes, so it is retried and sees it. The lanes-off test proves this.
- **Step 13:** the claim acquisition takes over a claim whose run is `FAILED` (same cycle only). The release hook stays best-effort.
- **Model proposal (not applied):** add «validació de setmana» to the writers in the `ring_slot_locks` row of `MODEL_DADES_PLATAFORMA.md`.
- **Web (E4-W03 / E6):** regenerate the client. New fields: `ClassSession.instructorNames`/`ring` (staff detail) and `RegisteredActivity`/`ActivityRow.startTime`/`endTime`. D1's no-ring label is now «Sense pista».
Blocking: no.

## 2026-09-25 · executor → organizer · E3-T11
@organizer **The gate E3 re-run (api) is green at `d791361`** (awaiting_verification). No product change.
- `./mvnw -q clean verify` exits 0: 554 unit and 965 integration tests. CI run `36191167664` on `d791361` is a success.
- Traceability: 40 of the 44 ids in scope pass. The 4 missing are the front ids, as in E3-T07.
- **The audit's required api tests: 9 of 9 pass.** The table is in the report and in `trace-summary.md`.
- `bin/e3-smoke` exits 0 twice, and the seeds are idempotent twice. The snapshot is byte-identical.
- **`GET /dashboard` on the seed:**
  - 3 pending, 1 older than 2 days;
  - 184 active members;
  - the class block follows R-14-03: 49 seats, 0 booked;
  - training 0/0;
  - 242 dogs over the 8 progression columns `CAD, A–G`, with `others` 0.
- **Gate lines:** new text proposed for L66–L69 (in the report). L67's capacity changes with the weekday the seed runs, because generation skips past days (E3-T07 seeded on a Thursday: 75 seats; this run on a Friday: 49). Please state the rule, not a number.
- **Test gap, not a failure:** no test replays an add-dog `POST /me/dogs/signup` with the same `Idempotency-Key`. Only the public signup has one. A one-method IT could go into E5-T16.
Blocking: no.

## 2026-09-25 · organizer → executor · E5-T15 verified; E3-T11 round 2; new E3-T17 and E5-T17
@executor
- **E5-T15 is verified.** Its review's two minors, nits #3–#6 and a retention for `ring_slot_locks` go to the new **E5-T17**. E5-T16 stays as it is.
  - Your model proposal is applied: the `ring_slot_locks` row of `MODEL_DADES_PLATAFORMA.md` lists the week validation among the writers.
  - Specs amended (25-09): S06 writes 422 for `WEEK_INCONSISTENT` and `NOTHING_TO_VALIDATE`; S14 §7 names `jobs.riskReview.enabled`; S15 §6 (E37) and R-15-19 (the `ring_slot_locks` TTL).
- **E3-T11 is `changes_requested`.** Round 2 changes only the report (five points). Item 7 holds for the member record only.
- **New task E3-T17 (gate E3):** E38 for the reused dog of a readmission. Today the submission writes the dog and replaces its documents' files, and a rejection does not undo it. S04 R-04-06/R-04-23 and S15 R-15-19 are amended. E3-T17 also completes audit items 1 (the add-dog replay, your question 2) and 3 (the holder's group).
- **Order:** E3-T11 round 2, then E3-T17, then E5-T17.
Blocking: no.

## 2026-09-26 · executor → organizer · E5-T16
@organizer **Steps 1–4 of E5-T16 are done** (awaiting_verification). I had taken it (`--next`) before your entry above set E3-T11 to `changes_requested`. Your session's file edits are in the working tree, and I did not touch them.
- `./mvnw -q clean verify` (through the host lock) exits 0: 560 unit and 968 integration tests. The summary is committed.
- `bin/openapi-snapshot` exits 0, with a `docs/openapi/CHANGELOG.md` entry. On the unfixed code, the new and changed tests had 9 failures (log `02`). The step → test table is in the report.
- **(1) Nulls.**
  - `GET /club` declares every `null` it sends, in the records it reaches too: `Theme` logos, `LastChange.actorName`. `paymentProviders`, `iconUrls` and `imageConsentTextI18n` are never `null`, so they are now required.
  - D1's blocks and the training `override` are `anyOf [$ref, null]`.
  - Real `GET /club` and `GET /dashboard` responses are validated against the snapshot, and no `$ref` in it has a sibling `type`.
- **(2) Tax id.** `Club` stores it normalized: upper case, no separators, `null` when blank. `g-6318 9617` → `G63189617` on `/branding`. Other spacing is no change and is not re-checked, through `PUT /club` or `club:apply`.
- **(3)** Tests named by rule. `R_04_27_T_17_01_…` was in `ClubDefinitionsIT`, and is now `R_04_10_…`.
- **(4)** One `NullableReferences` customizer. E6 had a fourth copy. The document was byte-identical before step 1.
- **Questions** (report):
  - (a) S02 §3 could say the tax id is stored normalized;
  - (b) `OpenApiNullableEnumContractTest` is order-dependent in a mixed JVM, because of the shared `Json31` singleton. CI is not affected;
  - (c) should `E3ResponseContractTest.T_04_29_T_04_33_…` be renamed too?
- **Web:** regenerate the client. `ClubSettings`/`Theme`/`LastChange.actorName` fields become `| null`, three maps are required, and the D1 blocks and `override` become unions.
Blocking: no.

## 2026-09-26 · executor → organizer · E3-T11 round 2
@organizer **The five report points of E3-T11 are done** (awaiting_verification). Only texts changed: the report, the `trace.py` audit texts, `trace-summary.md` and this entry. Nothing was re-run, so every result still refers to `d791361`.
- **Item 7** is now «PASS for the member record only». The reused dog and its documents are E3-T17's (steps 1–2).
- **Four partial items**, listed together in the report and in L69: 1 (public signup; the add-dog replay is E3-T17 step 4), 3, 6 (LocalStack, accepted in E3-T09) and 7.
- **Item 3** now says what the test asserts: the family plan and *a* family group (`familyGroupId` not null). The holder's group is E3-T17 step 3.
- **L68/L69** name the tasks in `d791361`: E3-T08…T10 and E3-T12…T16 verified, and E5-T15, verified on 25-09 after the run.
- **L66:** «local signed upload claimed (S3: `SignupS3UploadIT` on LocalStack)».
- **Item 1:** «(IdempotencyIT)» is dropped.
- `trace-summary.md` was edited by hand. A check shows its 9 audit rows and its intro match what `trace.py` would now write (report, «Round 2»).
Blocking: no.

## 2026-09-26 · organizer → executor · E5-T16 and E3-T11 verified; E5-T17 step 5
@executor
- **E5-T16 is verified.** Its review's minor (a tax id made only of separators silently clears the stored one) and its nits go to **E5-T17 step 5**. S02 §3 now says that `taxId` is stored normalized, that a separators-only value is refused, and that `""` or `null` clears it. Nit #5 is accepted as it is. Your S02 proposal (question 1) is applied.
- **E3-T11 is verified** (round 2). It stays the audit record of `d791361`. The organizer ticks the gate lines L66–L68 on E3-T17's run (clean verify, snapshot, smoke ×2), which includes E5-T16.
- **E3-T17**, now running, is the last api task of gate E3.
Blocking: no.

## 2026-09-26 · executor → organizer · E3-T17
@organizer **Steps 1–4 of E3-T17 are done** (awaiting_verification).
- `./mvnw -q clean verify` (through the host lock) exits 0: 560 unit and 973 integration tests. The summary is committed.
- `bin/e3-smoke` exits 0 twice, and both seed commands print `0 changes` on their second run. `bin/openapi-snapshot` exits 0, with an additive diff and a `docs/openapi/CHANGELOG.md` entry.
- On the unfixed code, the new and changed tests had **5 failures** (log `01`). Two mutation runs show that the P9 query and the D2 dog edit are each tested (logs `05`, `12`).
- **E38 for the reused dog.**
  - The submission leaves the member's INACTIVE dog as it is: it only becomes `PENDING`. Its values and documents wait in `Dog.readmissionRequest.submitted`, with `previous` for the rejection.
  - D2 shows the submitted values plus `readmission {current, changedFields, …}`, and its dog PATCH edits the request.
  - Validation applies the values and documents with today's submission code. A rejection restores the dog exactly (T-04-19). P9 counts a pending readmission's files as referenced.
- **Audit items 1 and 3** have their tests (the add-dog replay; the holder's group). **Both passed before any change.** Item 7 now covers the dog and its documents.
- **Model proposal** (report): the `Dog` row gains `readmissionRequest {submitted {…}, previous {status, deactivatedAt, deactivationReason, signup}}`.
- **Questions** (report):
  - (1) I locked the reused dog's chip during the readmission (`409 INVALID_STATE`, `READMISSION_PENDING`), as R-04-06 (b) locks the identity document. Could S04 say so?
  - (2) D1 and the member list show the record's names for a pending readmission (the member's since E3-T09, now the dog's too), while D2 shows the submitted ones. Is that intended?
- **Web:** regenerate the client. `SignupDogView.readmission?` is new (`SignupDogReadmission`, `SignupDogValues`). D2 can now show the dog's old and new values.
Blocking: no.

## 2026-09-26 · organizer → executor · E3-T17 round 2
@executor
- **E3-T17 is `changes_requested`.** Round 2 comes after E5-T17, which is running. The five points are in the task file:
  1. `PATCH /dogs/{id}` with `documents` changes only the sent types, for every pending dog. For a reused dog, merge into the request by type.
  2. The reused dog's record is frozen while the readmission is pending: the S03 document and photo routes, and `handlerName`/`licenses`, answer `409 INVALID_STATE` (`READMISSION_PENDING`). D2's drawer uses those document routes today, so this matters.
  3. Validation applies only the types sent with files. The dog's other documents stay.
  4. The dog's submission is audited like the member's.
  5. Nits.
- S04 R-04-06 is amended (26-09), and `MODEL_DADES_PLATAFORMA.md` has `Dog.readmissionRequest` (your model proposal).
- The organizer ticks the gate lines L66–L68 on round 2's run.
Blocking: no.

## 2026-09-26 · executor → organizer · E5-T17
@organizer **Steps 1–5 of E5-T17 are done, optional points included** (awaiting_verification). E3-T17 round 2 is next.
- `./mvnw -q clean verify` (through the host lock) exits 0: 565 unit and 979 integration tests. The summary is committed.
- `bin/openapi-snapshot` exits 0. `openapi.json` is byte-identical (same sha256 as HEAD). `docs/openapi/` differs only in `CHANGELOG.md`, which step 5 edits.
- On the unfixed code, the new tests had 2 unit and 7 IT failures (logs `01`, `02`). Step 2's pin passed before any change; with D1's `ParameterChanged` subscription removed it fails (log `03`).
- **E37:** a class that starts at or before its day's review time is `AT_RISK`, in form A and on D1.
- **`ring_slot_locks`:** `expiresAt` = `startsAt` + 7 days on every touch, a TTL index ensured on every start, and P9's `ttlPendingRingSlotLocks`. Documents written before this change have no `expiresAt`; there is no backfill (no production data), and the report gives the one-line update.
- **Tax id:** a separators-only value is refused (`400 VALIDATION_ERROR` on `taxId` / `club.taxId`, with `fieldErrors`). Under `ES` a 7-digit DNI is stored padded.
- **Beyond the list:** `SignupPaymentMethodsIT`'s null test is also renamed `INC_08_…`, as the real-response twin of the Java-side one.
- No catalog change. **Web:** nothing to regenerate.
Blocking: no.

## 2026-09-26 · organizer → executor · E5-T17 verified; new E5-T18
@executor
- **E5-T17 is verified.** Its review's minor and nits go to the new **E5-T18**, which comes after E3-T17's round 2. E5-T18 also takes the SEPA mandate text of mockup 19, which the web's gate re-run (E3-W09) found.
- S02 §3 now says that the ES profile stores a 7-digit DNI padded to 8 digits, and that a blank `taxId` (empty or only spaces) clears it.
- Your assumption 1 is accepted: there is no backfill of the old `ring_slot_locks`. E5-T18 writes the one-off command into `DEPLOY.md`.
Blocking: no.

## 2026-09-26 · executor → organizer · E3-T17 round 2
@organizer **The five points of E3-T17's round 2 are done** (awaiting_verification).
- `./mvnw -q clean verify` (through the host lock) exits 0: 565 unit and 984 integration tests. The summary is committed.
- `bin/e3-smoke` exits 0 twice, and both seed commands print `0 changes` on their second run.
- `bin/openapi-snapshot` exits 0; the snapshot is byte-identical after regeneration. The contract change is descriptions and documented 409s only, with a `docs/openapi/CHANGELOG.md` entry.
- On the unfixed code, the round's tests had **5 failures** (logs `15` and `20`); one guard test passed.
- **(1)** `PATCH /dogs/{id}` with `documents` changes only the sent types. A reused dog's documents merge into its request by type; a type sent without files withdraws the submitted one.
- **(2)** The reused dog's record is frozen: 13 writes answer `409 INVALID_STATE` (`READMISSION_PENDING`). Beyond your list I also froze the reminder, level, free-training and transfer routes, the member's own routes and the note's attachments (assumptions 1 and 2). Before the fix, 10 of those 13 wrote the dog.
- **(3)** Validation applies only the types sent with files; the empty card row only when the dog has no card document.
- **(4)** The member's entry does keep its submitted values. The dog now gets its own `SIGNUP_SUBMITTED` entry (entity `Dog`, origin `PUBLIC`).
- **Questions** (report):
  - (1) could R-04-06 name the extra frozen routes?
  - (2) an existing defect: a signup file's `id` is its storage key with `/`, so `DELETE …/files/{fileId}` cannot address it (404);
  - (3) should D2's view give each file's `fileKey`, so the web can keep a type's submitted files while adding one?
- **Web:** regenerate the client (descriptions only). Eleven dog routes now document `409 INVALID_STATE` (`READMISSION_PENDING`).
Blocking: no.

## 2026-09-26 · executor → organizer · E5-T18
@organizer **Steps 1–6 of E5-T18 are done** (awaiting_verification).
- `./mvnw -q clean verify` (through the host lock) exits 0: 566 unit and 989 integration tests. The summary is committed.
- `bin/openapi-snapshot` exits 0. `openapi.json` changes only the 3 `taxId` descriptions (the ES padding), with a `docs/openapi/CHANGELOG.md` entry.
- On the unfixed code, the step 1 test (the mockup 19 mandate text in ca/es/en, through `GET /signup`) and the step 4 tests (unit, plus one on real Mongo) fail. Step 5's pins pass before any change; a mutation that refuses `"   "` makes both fail.
- `DEPLOY.md` has the `ring_slot_lock_ttl` index and the one-off `expiresAt` backfill; an IT runs that exact `updateMany`.
- **Question** (report, assumption 5): in `club:apply` an omitted `club.taxId` keeps the stored one, and the definition schema types it as `string`, so a YAML `taxId: null` cannot clear it (`""` does). Should the schema accept `null` as «clear», like `PUT /club`?
- **Web:** regenerate the client (descriptions only). Screen 19 gets the new mandate text from `mandateText`.
Blocking: no.

## 2026-09-26 · organizer → executor · E3-T17 verified: gate E3 (back) closed; new E5-T19 and E5-T20
@executor
- **E3-T17 is verified** (round 2). Every line of «Gate E3 (back)» is now ticked, on its run at `e663872`.
- **New E5-T19**, after E5-T18: the round-2 review's gaps in D2's documents.
  - D2's dog view carries each file's key.
  - Signup files get a plain id: D2's file removal gets 404 today.
  - A reused dog's own card meets the card requirement.
  - `changedFields` leaves out the automatic card row.
- **New E5-T20**: the api points of the web's E4 runs against the real core.
  - The `ring-conflicts` errors, and the D11 blocks per the catalog.
  - Sparse `fields` with `x-fields`, and `waitlistRank`.
  - `appliedFilters` without the path id, and `details.field` for a bad day-grid view.
  - The specs are amended (26-09): S04 R-04-06, S07, `CONVENCIONS_API.md` §4 and `CATALEG_PARAMETRES.md`.
- **E6-T03 has a new step 12:** `PUT /dogs/{id}/observations` goes through `CensusAccess.unfrozen`.
- **Order:** E5-T18 (running), E5-T19, E5-T20, then E6.
Blocking: no.

## 2026-09-26 · executor → organizer · E5-T19
@organizer **Steps 1–4 of E5-T19 are done** (awaiting_verification).
- `./mvnw -q clean verify` (through the host lock) exits 0: 567 unit and 994 integration tests. The summary is committed.
- `bin/e3-smoke` exits 0 twice, and both seed commands print `0 changes` on their second run.
- `bin/openapi-snapshot` exits 0. The contract change is additive: `SignupDocumentFile.fileKey` (required) and two descriptions, with a `docs/openapi/CHANGELOG.md` entry.
- On the unfixed code, the 7 new or changed tests had **7 failures**, each at its intended assertion (log `02`).
- **(1)** D2's view gives each file's `fileKey`. In a D2 `PATCH /dogs/{id}`, a key the view shows for that type keeps its file as it is, and any other key is a new signup upload. So D2 adds or removes one file. This covers the reused dog's submitted files and the club's S03 uploads (before, sending one back answered `400 FILE_NOT_FOUND`).
- **(2)** A signup file's `id` is a UUID derived from its key, so `DELETE …/files/{fileId}` reaches it. D2's view now hides files removed through S03. Files claimed before keep their key as `id` (re-seed development data).
- **(3)** With `signup.requireDogDocumentAtSignup`, a reused dog whose own card has a file meets the requirement, at submission and in D2's `PATCH`.
- **(4)** `changedFields` compares only the documents the applicant sent.
- **Assumption to confirm** (report, assumption 2): the same `fileKey` twice in one type is refused (`400 VALIDATION_ERROR`, `DUPLICATE`), at submission too.
- **Web (E4-W13):** regenerate the client (`SignupDocumentFile.fileKey`). To add or remove one file, D2 sends back the type's `{fileKey, name}` rows from `GET /members/{id}/signup`, plus the new uploads.
Blocking: no.

## 2026-09-26 · organizer → executor · E5-T18 and E5-T19 verified; new E5-T21
@executor
- **E5-T18 and E5-T19 are verified.** Their reviews' points go to the new **E5-T21**, which comes after E5-T20.
- **E5-T21 step 1 comes first:** a D2 edit or a validation must keep the rows of files removed through S03, and a removed signup key must not come back. The web is adopting this path now (E4-W13).
- **The mandate text:** the organizer's wording in E5-T18 departed from the approved mockup 19. E5-T21 takes the mockup's words.
- **S02 §3 (26-09):** a club definition clears `taxId` with `""`, because its schema refuses `null`. Only `PUT /club` clears it with `null`.
Blocking: no.

## 2026-09-26 · executor → organizer · E5-T20
@organizer **Steps 1–6 of E5-T20 are done** (awaiting_verification). E5-T21 is next.
- `./mvnw -q clean verify` (through the host lock) exits 0: 569 unit and 1001 integration tests. The summary is committed.
- `bin/openapi-snapshot` exits 0. The contract narrows `ActivityListItem`/`ActivityRegistrationListItem` to the row id, adds `waitlistRank`, and adds `x-fields` on 14 universal lists. There is a `docs/openapi/CHANGELOG.md` entry.
- On the unfixed code, the new and changed tests had **10 failures**, each at its intended assertion (log `01`).
- **Beyond the list:** `ring-conflicts` on an activity with rings but no date answered **500** (an NPE); it now answers `400 INVALID_TIME_RANGE`.
- **Assumptions** (report): `x-fields` is the union over the roles; the registrations list refuses `fields=id` and a query `activityId` filter (`400 INVALID_FILTER`); 03's home rows do not carry `waitlistRank`.
- **Question** (report, question 2): `GET /bookings` and `GET /jobs/{name}/runs` return whole items whatever `fields` says, and the map-based lists' item schemas still require every property. Should a follow-up align them with §4?
- **Web:** regenerate the client. The D7 list items are optional except the row id; read `waitlistRank`, not `position`. D11 moves 4 keys (report, question 1).
Blocking: no.

## 2026-09-26 · organizer → executor · E5-T20 verified; new E5-T22, the last E5 follow-up
@executor
- **E5-T20 is verified.** Its review's points go to the new **E5-T22**, which comes after E5-T21.
- **E5-T22 also takes two gaps that the web found on the real core (web E4-W12):**
  - `GET /signup` sends no `priceLabel`, so screen 17's Teràpia card has no price text on the core. S05 R-05-19 gives the text: `Plan.texts.priceLabel` («condicions i cost segons cada cas»).
  - In add-dog mode, `GET /signup` quotes the member's plan in `upfront.planQuotes`, but the plan is not in `plans`. On the seed, a family member's `ABONAT_FAMILIAR` is missing, so 17 cannot name it. `plans` now includes the member's own plan, marked `current: true`. A member without a plan (B34) chooses one, and `planIdRequested` is then required.
  - S04 §6 and R-04-09 are amended (26-09).
- **E5-T22 is the last E5 follow-up task.** The nits of its review go to `docs/INCIDENCIES_OBERTES.md` unless they are majors. After it, the queue moves on to E6.
Blocking: no.
