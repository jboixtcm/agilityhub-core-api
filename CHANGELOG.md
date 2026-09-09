# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Changed

- E2-T06: Implement the S03 census with tenant-scoped members, dogs, family groups,
  document uploads, booking blocks, payment-method masking, owner profiles, tasks
  projections and audited outbox consumers. Preserve identity/onboarding compatibility,
  normalize country-profile phones, expose handler and license fields, retain bookings
  on level changes, and derive billing mode from plans. Add private local/S3 attachments,
  concurrency and permission tests, and refresh OpenAPI.

- E2-T08: Complete asynchronous list exports with tenant-scoped claims, per-club
  concurrency and account rate limits, streamed XLSX/PDF rendering, localized
  headers, masked values, seven-day signed downloads, retries and file cleanup.
  Add local/S3 storage with persistent development volumes, caller-owned job
  endpoints and POST export aliases;
  retain transactional completion audit/outbox and refresh OpenAPI.

- E2-T05: Implement tenant-scoped plans and dated prices, monthly billing modes,
  atomic price supersession and invoice locks, entry-fee and discount proposals,
  and localized public plans with club-key access. Add role, tenant, concurrency
  and pricing tests; refresh the OpenAPI snapshot.

- E2-T04: Implement instructor and administrator profiles, shared member-role
  assignment, tenant-scoped usage guards and concurrent last-administrator
  protection. Process member leave through the outbox, retain the last admin
  with an audit reason, and publish audited membership/profile changes atomically.
  Add team integration tests and update the OpenAPI snapshot.

- E2-T07: Implement reusable tenant-scoped member/dog lists, typed filters,
  facets, sparse fields, saved-view CRUD and masked XLSX/PDF exports. Queue
  exports above 5,000 rows for E2-T08, record completed exports in audit and the outbox,
  document list-provider integration, and refresh the OpenAPI contract.

- E1-T10: Widen identity account locales to all seven product languages and
  interpret timezone-free Learn imports in Europe/Madrid. Add regression tests,
  an opt-in private local email mailbox and a disposable E1 identity smoke script;
  refresh local authentication documentation and the OpenAPI snapshot.

- E1-T13: Browser refresh tokens now use host-only HttpOnly cookies with rotation,
  same-host request validation and logout/revocation clearing. BODY clients retain
  their token responses. Added audited platform-role GET/PUT endpoints with a
  concurrent last-admin guard, an idempotent deployment bootstrap CLI, proxy
  recipes, regression tests and the regenerated OpenAPI contract.

- E1-T11: Make OpenAPI model properties required by default, with explicit optional
  fields across existing contracts, preserved empty required arrays, and regression
  checks for model annotations and byte-identical snapshot regeneration.

- E1-T03 Round 2: Return the approved `WEBHOOK_SIGNATURE_INVALID` error with HTTP 401
  and record signature failures as security events. Audit account email suppression
  as `ACCOUNT_EMAIL_STATUS_CHANGED`, with webhook regression tests and the updated OpenAPI contract.

- E1-T01 Round 2: Align onboarding schemas and the postpone route with S01 v0.3;
  move public magic-link requests to `/api/v1/auth/magic-link` with the existing
  authentication IP quota; add password, verification, onboarding and gender fields
  to `Me`, with contract, serialization and tenant/role tests.

### Added

- E1-T14: Publish green main builds to private GHCR for amd64 and arm64. Add a
  consumer Compose stack with image-contained fictional seeds, private local mail,
  up/down helpers and an image mode for the disposable E1 smoke; document pulls,
  local proxy hosts and the staging image handoff. Disable Mongo's wall-clock TTL
  worker only in the disposable integration-test server to preserve clock-controlled fixtures.

- E2-T03: Tenant-scoped levels, rings and FAQs with localized text, ordering,
  usage guards, optimistic edits, transactional audit/outbox records and reduced
  reader views. Add pure class/ring capacity and D3 coverage calculations. Remove
  the obsolete club-level difficulty field and align the OpenAPI contract.

- E2-T02: Audited parameter and club settings APIs with scoped overrides, retained
  reset history, optimistic concurrency, immediate cache refresh, self-service
  module toggles, labeled holidays and country-profile lookups. Suspended clubs
  now reject identity sessions. Added tenant/role, rollback and concurrent-edit
  tests and updated the OpenAPI contract.

- E1-T07: Learn CSV import CLI with unchanged bcrypt credentials, idempotent
  account linking, explicit audited platform-admin grants, guest exclusion,
  write-free previews and reports without personal data. Added a fictional
  50-account fixture and T-01-14 import, login, conflict and rollback tests.

- E1-T09: Fictional Cànic/minimal account definitions,
  tenant role reconciliation, insert-only credentials and onboarding defaults,
  environment password guards, and an accounts-only identity seed alias.
  Updated seed schema, regression tests and local setup instructions. Aligned the
  Cànic parameter fixture and public audit-action enum with the approved catalogs.

- E1-T06: First-access onboarding with versioned platform and tenant-specific club
  consent history, configurable profile fields, mandatory initial acceptance and bounded
  postponements for policy renewals. Account/member updates and completion audits share
  a transaction; integration tests cover concurrency, rollback, roles and tenant isolation.

- E1-T05: OIDC discovery, S256 authorization-code flow, scoped userinfo and RP logout,
  with cookie-bound apps/id login continuation and five configurable first-party clients.
- AES-256-GCM encrypted Mongo signing-key ring and `identity:rotate-keys`, retaining
  the previous RSA verification key; code/session/rotation tests and `bin/oidc-smoke`.

- E1-T04: Tenant-bound impersonation grants with member-only JWTs, no refresh tokens,
  live grant validation, revocation events, and admin/member attribution on audited writes.
- Single-use 60-second app handoff codes, stored as hashes and bound to the account,
  club, destination client and source session, with verified destination URLs and security telemetry.
- Grant integration tests for roles, tenants, expiry, concurrency, rollback and credential
  isolation; refreshed the OpenAPI snapshot for the implemented identity contracts.

- E2-T01: S02/S03/S05/S14 API contracts for settings, census, catalogs, saved views,
  audit, exports, privacy requests and dashboard schemas, with standard 501 stubs.
- Universal list metadata, role-specific response projections, canonical catalog errors,
  explicit binary/queued export responses, and public postal-code/API-key contracts.
- Contract and response fixtures covering every new operation's role/tenant boundaries,
  module guards, wire formats and sensitive-field allowlists; updated OpenAPI snapshot.


- E1-T02: Idempotent account creation, tenant membership services, single-use magic
  links through SYSTEM email, sliding refresh rotation, device sessions, progressive
  login lockout, and per-email/IP magic-link quotas.
- Optional password changes with HIBP checks, remembered profiles, account updates,
  and account-wide or club-specific session revocation with transactional events.
  Added identity integration tests, a curl/mailbox smoke test, and Cànic branding city.

- E1-T03: SendGrid transactional email with local/test sinks, fixed N-25/N-26/N-27
  copy in Catalan, Spanish and English, Thymeleaf club branding, and a SYSTEM
  notification log with transactional outbox state events.
- Signed SendGrid callbacks with atomic event deduplication, delivery tracking,
  account email suppression and audit; environment-only provider configuration,
  startup guards, mocked-provider tests, and the webhook OpenAPI contract.
- Synchronized the executable parameter catalog with the organizer-approved
  `signup.onboardingFields` and `legal.maxPostpones` entries already in the catalog document.

- E1-T01: Complete S01 identity OpenAPI contract with typed request/response schemas,
  five documented token grants, OIDC routes, account/profile/session/onboarding APIs,
  handoff, impersonation and Learn account synchronization endpoints.
- Standard localized 501 responses for pending identity implementations, tested account,
  scope, role and tenant boundaries, and the R-01-15 `Me` bootstrap shape with profiles
  and features. Existing club password/refresh grants and public JWKS remain active.

- E0-T14: Five source-linked playbooks for entities, endpoints, schedulers, event
  consumers and task reports, with E0 examples and explicit boundaries for S15 jobs.

- E0-T12: OpenAPI 3.1 at `/api/v1/openapi.json` in local/test, context tags, bearer security,
  shared error responses, token/JWKS contracts and an endpoint changelog.
- Reproducible Testcontainers snapshot generation and CI regeneration/diff enforcement.
- Security-event retention now follows the 90-day catalog default, retains system overrides,
  and updates existing TTL indexes when retention changes.

- E0-T11: Configurable Bucket4j quotas for token, branding, public and account routes,
  standard rate-limit errors with Retry-After, and cached CORS for registered club and platform hosts.
- Production HSTS, CSP and referrer headers; global security events for failed logins,
  refresh reuse, rate limits and tenant mismatches with configurable TTL and no stored credentials.
- Private actuator listener on port 8081 with health, info and Prometheus; security integration
  tests, narrowly scoped generated-PEM scan exceptions, and club-schema packaging for Docker.

- E0-T10: Non-web CLI dispatcher with club apply/export and the local/test identity seed command;
  JSON Schema 2020-12 validation, per-section diffs, dry runs, and transactional idempotent applies.
- Cànic, minimal, and template club seeds, admin provisioning, host uniqueness, parameter history,
  catalog-only outbox events, audit summaries, and branding/parameter fixture regression tests.
- Organizer-approved defaults for all 14 previously unspecified parameters, including three-language
  signup texts, leave reasons, dog documents, and signup rate limits; synchronized catalog contract.

- E0-T09: Global accounts, tenant-scoped memberships, Learn bcrypt verification,
  argon2id password creation, SAS password/refresh grants and RSA JWT/JWKS.
- Protected bearer-token routes, host-scoped `/me`, hashed refresh rotation with
  family reuse revocation, and local/test-only account seeding with explicit passwords.
- Identity normalization, credential, tenant/role, JWT/key, refresh, and seed tests;
  local authentication documentation and the updated OpenAPI snapshot.

- E0-T08: UTF-8 ICU message source with all 241 catalog errors in Catalan,
  Spanish and English; account/club-aware response locales and scoped recipient locales.
- Club time-zone date/time, exact money and localized duration formatting, plus
  message parity, ICU plural/select, locale isolation and HTTP integration tests.
- Approved `IDEMPOTENCY_KEY_REUSED` conflicts with `DIFFERENT_REQUEST` / `IN_PROGRESS`
  reasons and localized messages; response replay preserves the original language.

- E0-T07: Reusable audit aspect, manual writer and tenant-scoped last-change query,
  append-only Mongo entries with startup indexes, persisted in the aggregate transaction.
- Detached annotated audit snapshots with nested diffs, IBAN/document masking and
  hidden secrets; rollback, tenant/platform isolation and audit-action coverage tests.
- E0-T07 Round 2: Audit insert failures roll back aggregate and outbox writes;
  audit storage, annotations and queries use S14's entityType, entityId and changes.path names.

- E0-T06: One public module enum with catalog-checked dependencies, self-service
  flags and MINIM/CANIC presets; dependency validation with missing/dependent details.
- Tenant-aware module guards for services and schedulers, plus controller/method
  interception before request body validation using the standard MODULE_DISABLED error.
- S02 module catalog, dependency and integration tests covering module toggles,
  tenant/role checks, cumulative annotations and unaffected endpoints.

- E0-T05: Club and scoped parameter persistence with startup indexes, the complete
  146-key parameter catalog and Markdown contract, typed validation, immutable
  cached club configuration, and outbox-driven cache invalidation.
- Tenant context/filter with JWT/verified-host resolution, local host override,
  request cleanup, and repository isolation for reads, inserts, replacements and
  deletes; public branding and PWA manifest with ETags and secret-free responses.
- ES/GENERIC country profiles, DNI/NIE and IBAN checks, E.164 normalization,
  attributed full GeoNames Spanish postal data, and S02 unit/integration tests.

- E0-T04: Shared Money arithmetic and ICU formatting, localized text fallback,
  all 240 catalog error codes, consistent API errors with request trace IDs,
  and injectable club-local clocks.
- Tenant/account-scoped Mongo idempotency with transactional response replay,
  conflict detection, and 24-hour retention; transactional outbox with consumer
  checkpoints, fenced claims, exponential retries, 90-day retention, and metrics.
- Error/event catalog contracts and unit/Mongo integration tests for shared
  primitives, authorization/isolation, rollback, concurrency, and retries.

- E0-T03: GitHub Actions build, Testcontainers and architecture checks, JaCoCo
  artifact upload, independent Gitleaks scanning, and a conditional OpenAPI diff hook.
- Weekly Maven and GitHub Actions Dependabot updates and documented `main`
  branch protection requiring one review and both CI checks.
- Corrected JaCoCo package matching so domain/application line and branch gates
  and API line gates are enforced, including nested packages; excluded wiring,
  application entry points, and generated code from coverage analysis.
- E0-T02: Local Docker Compose stack with a persistent MongoDB 7 replica set,
  PRIMARY readiness, and a multi-stage Java 21 API image running as a non-root user.
- Shared Testcontainers integration-test base, mutable test clock, JSON fixture
  loader, and Mongo transaction commit/rollback smoke tests executed by Maven Failsafe.
- Mongo transaction manager and UTC clock configuration; local Mongo connectivity
  and Docker/test instructions with documented environment defaults.
- E0-T01: Java 21 / Spring Boot 3.5 Maven skeleton with pinned dependencies,
  Maven wrapper, build metadata, coverage checks, and an optional mutation profile.
- All 15 bounded contexts and four layers, protected by ArchUnit architecture rules.
- Public `GET /api/v1/health`, endpoint security and tenant-independence tests,
  and the initial generated OpenAPI snapshot.
- Local/test/staging/prod configuration, environment template, editor and ignore
  conventions, and five-command getting-started instructions.
