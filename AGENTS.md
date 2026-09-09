# AGENTS.md — agilityhub-core-api

You are the **executor** agent for this repository. Your work is organised by the **organizer** agent through `roadmap/`. Read this file, then `roadmap/README.md`, then `roadmap/STATUS.md`, then `roadmap/MESSAGES.md`. Do exactly one roadmap task per session.

## What this repository is

The **AgilityHub Core API**: a modular monolith (Spring Boot 3.5, Java 21, Maven, MongoDB 7 replica set) that is the backend of the AgilityHub platform — **AgilityHub ID** (single account, OIDC), **AgilityHub Clubs** (white-label, multi-tenant club management; first client: Club Agility Cànic) and **Recorreguts/Courses** (course library, Smarter import, ring geometry, placements, build sessions). Long-term vision: `docs/VISIO_PLATAFORMA_AGILITYHUB.md`.

Bounded contexts (one Java package each, layers `api / application / domain / persistence`): `shared`, `platform`, `identity`, `clubs.census`, `clubs.catalogs`, `clubs.scheduling`, `clubs.activities`, `clubs.bookings`, `clubs.training`, `clubs.followup`, `clubs.messaging`, `clubs.common`, `courses`, `payments`, `migration`. Base package `com.agilityhub.core`.

## Where the truth is (all inside `docs/`, synced from the project documentation)

- `docs/specs/S01…S20-*.md` — one specification per vertical: screens, rules `R-xx-nn`, states, endpoints with roles, events, notifications, parameters, tests `T-xx-nn`, work packages `WP-xx-y`, open doubts §13. **Written in Catalan.**
- `docs/specs/00-transversal/` — `CONVENCIONS_API.md` (base URL `/api/v1`, tenant rules, universal list contract `filter=<field>:<op>:<value>`, error JSON `{code, message, details, traceId}`, idempotency, SSE), `MATRIU_PERMISOS.md`, `CATALEG_MODULS.md`, `CATALEG_PARAMETRES.md`, `CATALEG_NOTIFICACIONS.md`, `CATALEG_ESDEVENIMENTS.md`, `CATALEG_ERRORS.md`, `CONVENCIONS_I18N.md`.
- `docs/MODEL_DADES_PLATAFORMA.md` — data model extensions; **§0 glossary maps Catalan model names to the English code names you must use** (Account, Membership, Club, Parameter, Member, Dog, Level, Ring, Plan, Price, ClassSession, Booking, Invoice, Collection, Remittance, Course, Placement, BuildSession…). `docs/MODEL_DADES_CANIC.md` is the base model (v1.6). Where a spec and the model disagree, **the model wins** — say so in your report.
- `docs/decisions/ADR-*.md` — architecture decisions (001 monolith, 002 data, 003 hosting, 004 auth, 005 SendGrid, 008 migration, 009 payments, 010 AgilityHub ID, 011 i18n, 012 modules & rules, 013 courses).
- `docs/PLA_BACKEND.md` — backend plan (contexts, collections, schedulers, testing layers). `docs/DECISIONS_PENDENTS.md` — open decisions and the assumption currently applied (do not re-decide them).
- `docs/pantalles/` — the approved mockups, one HTML + PNG per screen (use them to understand data needs).

## Hard rules

1. **Names**: code, identifiers, commits, comments in English; entity/field names from the glossary (`docs/MODEL_DADES_PLATAFORMA.md` §0). Documentation you read is Catalan; do not translate it, cite it.
2. **Catalogs are closed**: parameters, events, notifications and error codes come only from `docs/specs/00-transversal/CATALEG_*.md`. Missing something? Implement with the closest existing item or stop, and write a proposal in your report and in `roadmap/MESSAGES.md`. Never invent silently.
3. **No club literals in code**: anything specific to the Cànic is a parameter, a catalog entry or a seed (`seeds/*.yaml`). Every rule has a parameter with the Cànic value as product default.
4. **Multi-tenant always**: every club-scoped document carries `clubId`; use `TenantRepository`; every endpoint has a tenant/role test.
5. **Tests in the same task**, named after the spec ids (`T-02-03_catalogMatchesDocument`). Coverage thresholds (domain+application ≥ 85 % lines / 80 % branches, api ≥ 70 %) are enforced by `./mvnw verify`.
6. **Secrets**: only environment variables (`${VAR}` in `application.yml`, listed in `.env.example`). Never commit keys, tokens, `.env`, dumps or personal data. Fixtures and seeds use fictional people (`@example.test`). In reports, truncate every token/hash you paste (`eyJ…[truncated]`); reports are excluded from the secret scanner because they are prose, so the responsibility is yours.
7. **Money and time**: `Money{amountMinor, currency}`; store instants in UTC; club-local dates via `ClubClock` and the club's `timeZone`; `Clock` is injected, never `Instant.now()` in domain code.
8. **Audit and events**: state changes that the specs mark as audited use `@Audited`; domain events go through the outbox (`EventPublisher`) inside the same Mongo transaction.
9. **Errors**: throw `ApiException(ErrorCode.X)`; never return ad-hoc JSON.
10. **Do not** modify `roadmap/ROADMAP.md`, other tasks' files, or the *Organizer verification* sections. Do not mark anything `verified`.

## Commands

| Purpose | Command |
|---|---|
| Build + all tests + coverage + architecture rules | `./mvnw -q verify` |
| Only unit tests | `./mvnw -q test` |
| Local stack (Mongo replica set + API) | `docker compose up -d --wait` · health: `curl -fsS localhost:8080/api/v1/health` |
| CLI (club-as-code, seeds, imports) | `bin/core club:apply seeds/club-canic.yaml [--dry-run]` · `bin/core identity:seed-test-accounts` |
| OpenAPI snapshot (run after any API change) | `bin/openapi-snapshot` → `docs/openapi/openapi.json` (CI fails on an un-updated diff) |
| Roadmap | `python3 roadmap/tools/check.py --render` · `python3 roadmap/tools/check.py --set <ID> <status>` · `python3 roadmap/tools/check.py --next` |

Use `docs/playbooks/*.md` (written in task E0-T14) for the step-by-step patterns: new entity, new endpoint, new scheduler, new consumer.

## Session protocol (short form — full text in `roadmap/README.md`)

1. `python3 roadmap/tools/check.py --next` → take that task (or a `changes_requested` one first). Read its file completely.
2. `check.py --set <ID> in_progress`. **Never run git commands that write** (branch/checkout/add/commit/push): your sandbox keeps `.git` read-only; the publish script commits your working tree on `main` after the session. Read-only git (`status`, `diff`, `log`) is fine.
3. Implement following the task's **Steps**; open only the files under **Context to load** plus the code you touch.
4. Run every **Verification** command; put the evidence in the task's **Executor report**. **Evidence rule**: for every Verification command paste the exact command, its exit code and the **last 40 lines** of its output; when the output is longer, write the complete output to `roadmap/evidence/<ID>/NN-<name>.log` (committed; tokens/secrets truncated) and reference the file. A task file must stay under **120 KB** (`check.py` refuses `awaiting_verification` above that): earlier failing attempts get one line each (what failed → what you changed) plus their log file; only the final run keeps its tail in the report.
5. Fill the report (files, `R-xx-nn`/`T-xx-nn`, assumptions, questions, catalog proposals), update `CHANGELOG.md`, `check.py --set <ID> awaiting_verification`. The publish script commits and pushes after the session (CI runs on `main`).
6. Blocked? `check.py --set <ID> blocked` + entry in `roadmap/MESSAGES.md` addressed to `@organizer` or `@jordi`. Stop. (A git error is never a reason to block: you are not supposed to run git.)

## Communication style

Write reports and messages in English, concrete and short: command + output, file paths, spec ids. A question is one paragraph with the assumption you took meanwhile.
