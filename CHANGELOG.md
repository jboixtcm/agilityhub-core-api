# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

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
