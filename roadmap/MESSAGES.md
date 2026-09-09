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
