# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

- E0-T08: UTF-8 ICU message source with all 241 catalog errors in Catalan,
  Spanish and English; account/club-aware response locales and scoped recipient locales.
- Club time-zone date/time, exact money and localized duration formatting, plus
  message parity, ICU plural/select, locale isolation and HTTP integration tests.
- Approved `IDEMPOTENCY_KEY_REUSED` conflicts with `DIFFERENT_REQUEST` / `IN_PROGRESS`
  reasons and localized messages; response replay preserves the original language.

- E0-T07: Reusable audit aspect, manual writer and tenant-scoped last-change query,
  append-only Mongo entries with startup indexes, and after-commit persistence.
- Detached annotated audit snapshots with nested diffs, IBAN/document masking and
  hidden secrets; rollback, tenant/platform isolation and audit-action coverage tests.

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
