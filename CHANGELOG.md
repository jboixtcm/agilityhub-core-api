# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

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
