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
