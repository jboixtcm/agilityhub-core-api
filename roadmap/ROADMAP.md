# ROADMAP — agilityhub-core-api (maintained by the organizer)

Stages come from `docs/PLA_DESENVOLUPAMENT.md` (E0–E12, no dates; goal: the Cànic fully operational on the new platform by end of October 2026). A stage is **closed** when all its tasks are `verified` and the gate checklist passes with Jordi. Task files exist only for opened stages; the organizer creates the next ones when a gate is passed (so that verified results and new decisions are incorporated).

Live status: `STATUS.md` (generated). Questions and decisions: `MESSAGES.md`. Open product decisions and the assumptions currently applied: `docs/DECISIONS_PENDENTS.md`.

## E0 · Foundations (thread A) — OPEN

Order: T01 → T02 → T03 → T04 → T05 → T06 → T07 → T08 → T09 → T10 → T11 → T12 → T14 · T13 when Jordi provides SSH/DNS.

| ID | Task | Depends on |
|---|---|---|
| E0-T01 | Repository skeleton, Maven build, context architecture test | repo created |
| E0-T02 | Docker Compose + Testcontainers base + transaction smoke test | T01 |
| E0-T03 | CI with coverage thresholds, gitleaks, OpenAPI diff hook | T02 |
| E0-T04 | `shared`: Money, LocalizedText, error contract, idempotency, Clock, outbox | T02 |
| E0-T05 | `platform`: Club, parameters + catalog (T-02-03), config cache, CountryProfile, tenant, `/branding` | T04 |
| E0-T06 | Modules: `@RequiresModule`, dependency validation | T05 |
| E0-T07 | Audit base: `@Audited`, AuditDiff masking, coverage contract | T05 |
| E0-T08 | Backend i18n (ICU, ca/es/en), ClubFormats | T04 |
| E0-T09 | `identity` skeleton: Account, Membership, password grant, JWT, `/me` | T05, T07 |
| E0-T10 | Club-as-code: CLI, `club:apply`, schema, seeds canic/minim/template | T05, T06, T09 |
| E0-T11 | Security baseline: rate limits, CORS per club, headers, SecurityEvent | T09 |
| E0-T12 | OpenAPI 3.1 snapshot + CI diff | T09 |
| E0-T13 | Staging deployment + backup/restore + DEPLOY.md | T03, T10, Jordi |
| E0-T14 | Playbooks (entity, endpoint, scheduler, consumer, checklist) | T10, T11, T12 |

### Gate E0 (checked by the organizer with Jordi) — organizer 2026-09-10 08:58: ready to promote (`gate/E0` at `061671a`, CI green) once Jordi's Mac check passes
- [x] `docker compose up -d --wait` starts api + mongo; `/api/v1/health` is `UP` — E3-T06 evidence `21-fresh-stack.log` (empty database, with/without `Host`); the 09-09 `500` was a stale native Java listener on `[::1]:8080` (INC-01 closed) → Jordi: kill it and check with `curl -4 -fsS http://127.0.0.1:8080/api/v1/health`.
- [x] `bin/core club:apply seeds/club-canic.yaml` and `seeds/club-minim.yaml` applied; second run reports 0 changes (E0-T10, E2-T11 idempotency; `SEED_PASSWORD` required since E1-T09 — INC-05).
- [x] `/branding` returns the Cànic theme for its host and the AgilityHub theme for the minimal club; unknown host → `404 UNKNOWN_HOST` (E0-T05; re-proven in E3-T06 `21-fresh-stack.log`).
- [x] Password login with `admin@example.test` at the Cànic host → `/me` with `roles [ADMIN]`; same account at the minimal host → `403 NO_MEMBERSHIP` (E0-T09/E1 ITs, `bin/e1-smoke`).
- [x] CI green with T-02-01/02/03/04/06/07/12, tenant-repository test, `RequiresModuleIT`, `AuditContractTest`, `OutboxIT`, `IdempotencyIT`, `RateLimitIT`, message parity (`061671a`: 349 unit + 430 IT).
- [x] `docs/openapi/openapi.json` committed and the CI diff proven (E0-T12; snapshot job in `ci.yml`).
- [ ] Staging answers (`/health`) and one backup has been restored — **deferred by Jordi** (E0-T13 waits for SSH/DNS); not a blocker for the tag.
- [ ] Playbooks written — `docs/DEPLOY.md` covers compose, proxy, storage and the gate check; backup/restore playbook lands with E0-T13.

## E1 · AgilityHub ID (thread C) — OPENED 2026-09-06 (E1-T01 ready; the rest open in order as their dependencies are verified; E0-T13 staging deferred until Jordi provides SSH/DNS)
Decisions needed first: A1 (applied), A2, A3, A4 of `docs/DECISIONS_PENDENTS.md`; SendGrid account.
Planned tasks: E1-T01 contract S01 · E1-T02 identity core (magic link, sliding refresh + rotation, lockout, `/me/*`, SecurityEvent) · E1-T03 email base with SendGrid (S11 WP-11-B0: N-25/26/27) · E1-T04 impersonation + handoff · E1-T05 OIDC provider (discovery, PKCE, userinfo, JWKS rotation, seed clients) · E1-T07 onboarding «Completa el teu perfil» (`Account.onboardingPending`) · E1-T08 Learn import CLI (dry-run, bcrypt hashes untouched) · E1-T09 Learn adapter PR (Laravel repo, not deployed) · E1-T10 integration + gate. (Front counterparts live in the web repo.)

Added 06-09: **E1-T11** OpenAPI required-by-default for DTO schemas (springdoc customizer + contract test) — from the web E1-W06 finding on the branding schemas.

Added 09-09: **E1-T13** A1 cookie delivery of the refresh token for browser clients (`tokenDelivery = COOKIE`) + A3 platform-roles API/CLI; DEPLOY proxy recipe.

**Gate E1 checklist (organizer, 2026-09-09)** — backend side: [x] E1-T01…T07, T09, T10, T11, T13 verified · [x] `bin/e1-smoke` green locally (magic link, cookie sessions, lockout, impersonation, handoff, OIDC, Learn dry-run) · [ ] staging smoke + real SendGrid N-25 (needs E0-T13: SSH/DNS from Jordi) · [ ] E1-T08 Learn adapter + `POST /platform/accounts` / `PUT /accounts/{id}/password` implemented (currently 501; needs the Laravel repo) · [ ] web E1-W07 (cookie mode) + E1-W04 (front integration) verified. E2 work continues in parallel; the gate closes when the four open boxes are ticked.

Added 09-09: **E1-T14** CI publishes the api image to GHCR + `docker-compose.consumer.yml` (web integration E1-W04 and staging run the published image).

## E2 · Census and catalogs (thread A) — opens after gate E0 (front with mocks; integration needs E1)
Planned tasks: E2-T01 contracts (S02-B, S03, S05, S14) · E2-T02 parameters API (`/parameters*`, `/club*`, holidays, postal codes) · E2-T03 levels, rings, FAQ + `CapacityCalculator` · E2-T04 team and roles · E2-T05 plans and prices · E2-T06 census domain + endpoints (members, dogs, family groups, documents, booking block) · E2-T07 universal list + saved views + sync exports · E2-T08 async export engine · E2-T09 audit queries · E2-T10 Playoff mapping + census importer (dry-run on anonymised fixtures) · E2-T11 seeds (Cànic catalogs + `demo-seed` 184 members / 242 dogs) · E2-T12 club pages (`ClubPage`: rules, privacy, image consent, welcome guide; added 09-09). · E2-T13 CI stability: bounded export size check (added 09-09 after the first red CI post-E2-T06).

### Gate E2 (back) — checked by the organizer 09-09 23:20
- [x] Census complete (members, dogs, family groups, documents/attachments, booking block, roles), universal lists with saved views and sync/async exports; INSTRUCTOR projection without financial fields (E2-T06/T07/T08).
- [x] Catalogs (levels, rings, FAQ, plans/prices, team) and parameters generated from the catalog with `lastChange`; club pages (E2-T02…T05, T09, T12).
- [x] `migration:playoff --dry-run` on anonymised fixtures: report without errors, no real data in the repo (E2-T10).
- [x] `club:apply` + `seed:demo` idempotent: 184 active / 194 members, 242 dogs (E2-T11) — local stack; the staging load waits for E0-T13.
- [ ] D5/D15 < 500 ms with two filters on the demo seed — measured by the web integration task E2-W07.

## E3 · Public signup + dashboard (thread A) — task files installed 09-09 (`not_open`; the organizer opens E3-T01 at gate E2)
Planned tasks: E3-T01 contract S04 (`/signup*`, `/checkout-sessions`, `/me/dogs/signup`, `/members/{id}/signup|validation|rejection`, virtual list fields) + dashboard schema check against S14 §6 · E3-T02 signup domain (id documents/phones/postal codes per country profile, `FirstMonthCalculator`, `UpfrontAllocator`, `FamilyHolderMatcher`, `SignupPlanCatalog`, upfront lines, state machines; T-04-01…10) · E3-T03 signup endpoints (public flow, identity checks, uploads, family lookups, D2 validation/rejection, add-dog, `PaymentProvider` + `FakeCheckoutGateway`, rate limits, N-01/02/03/37/39, seeds; T-04-11…28) · E3-T04 dashboard back (`DashboardQuery` + cache/invalidation, `RiskCardBuilder` over S06 ports, `DogActivityQuery`, `/dashboard`, `/dashboard/counters`; T-14-01…06, 11, 22, 23) · E3-T05 integration (`bin/e3-smoke`, D1 seed values, DEPLOY, gate checklist).

Added 09-09 (night): **E3-T06** hardening of INC-01…04 + INC-06 (health independent of tenant/data, 500s logged, truthful compose healthcheck, `MONGO_PORT`, `scope` always present) — order 5, so it runs right after E3-T01; it unblocks the gate E0 promotion.

### Gate E3 (back — checked by the organizer)
- [ ] `bin/e3-smoke` green twice on the local stack: public signup (family group found, SEPA without IBAN → warning) → D1 pending → D2 validation → N-02 in the mailbox → welcome link → `/me`; add-dog → N-37; rejection → N-03; `signup.enabled=false` → `SIGNUP_CLOSED`.
- [ ] `GET /dashboard` with the seed: `pendingSignups` real, `activeMembers` real, class/training blocks `null`/0 (ports until E4/E5), `dogsByLevel` real.
- [ ] CI green; OpenAPI snapshot staged for the web (E3-W03).

## E4 → E12 (summary; details in `docs/PLA_DESENVOLUPAMENT.md` and the Catalan backlog `docs/backlog` when synced)
- **E4** Planning + activities (S06, S07) — gate: a week generated from templates and validated; a class with bookings cancelled with events.
- **E5** Bookings + free training + scheduler framework (S08, S09, S15 P1/P6/P7/P9) — gate: full booking/cancel/waitlist/training cycle in staging with jobs running; k6 peak test passes; zero overbooking under concurrency.
- **E6** Attendance + follow-up (S10, S15 P3/P8).
- **E7** Communications (S11: engine, templates, SMS Twilio, push, email bounces, mass communications; S15 P4).
- **E8** Billing, payments (SEPA pain.008, Stripe, manual), packs, inactivity and leave (S12, S13, S15 P5/P10, S18 WP-C) — gate: simulated + generated remittance, XML validated, rollback, Stripe test webhook.
- **E9** Courses and build sessions (S16: port of the web-planner through `CoreApiStore`, Unity export contract) — independent thread from E0.
- **E10** Club console (S17: platform API, D19, on-demand TLS, cloning, support access).
- **E11** Hardening + QA (S14 WP-D RGPD, security review, backups, Lighthouse, E2E, migration rehearsal, guided QA with Josep).
- **E12** Migration and go-live (S18 WP-E: cut-over on a Sunday before 20:00, welcome batches, first supervised remittance).
