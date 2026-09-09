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

### Gate E0 (checked by the organizer with Jordi)
- [ ] `docker compose up -d --wait` starts api + mongo; `/api/v1/health` is `UP`.
- [ ] `bin/core club:apply seeds/club-canic.yaml` and `seeds/club-minim.yaml` applied; second run reports 0 changes.
- [ ] `/branding` returns the Cànic theme for its host and the AgilityHub theme for the minimal club; unknown host → `404 UNKNOWN_HOST`.
- [ ] Password login with `admin@example.test` at the Cànic host → `/me` with `roles [ADMIN]`; same account at the minimal host → `403 NO_MEMBERSHIP`.
- [ ] CI green with T-02-01/02/03/04/06/07/12, tenant-repository test, `RequiresModuleIT`, `AuditContractTest`, `OutboxIT`, `IdempotencyIT`, `RateLimitIT`, message parity.
- [ ] `docs/openapi/openapi.json` committed and the CI diff proven.
- [ ] Staging answers (`/health`) and one backup has been restored (or explicitly deferred by Jordi).
- [ ] Playbooks written.

## E1 · AgilityHub ID (thread C) — OPENED 2026-09-06 (E1-T01 ready; the rest open in order as their dependencies are verified; E0-T13 staging deferred until Jordi provides SSH/DNS)
Decisions needed first: A1 (applied), A2, A3, A4 of `docs/DECISIONS_PENDENTS.md`; SendGrid account.
Planned tasks: E1-T01 contract S01 · E1-T02 identity core (magic link, sliding refresh + rotation, lockout, `/me/*`, SecurityEvent) · E1-T03 email base with SendGrid (S11 WP-11-B0: N-25/26/27) · E1-T04 impersonation + handoff · E1-T05 OIDC provider (discovery, PKCE, userinfo, JWKS rotation, seed clients) · E1-T07 onboarding «Completa el teu perfil» (`Account.onboardingPending`) · E1-T08 Learn import CLI (dry-run, bcrypt hashes untouched) · E1-T09 Learn adapter PR (Laravel repo, not deployed) · E1-T10 integration + gate. (Front counterparts live in the web repo.)

Added 06-09: **E1-T11** OpenAPI required-by-default for DTO schemas (springdoc customizer + contract test) — from the web E1-W06 finding on the branding schemas.

Added 09-09: **E1-T13** A1 cookie delivery of the refresh token for browser clients (`tokenDelivery = COOKIE`) + A3 platform-roles API/CLI; DEPLOY proxy recipe.

**Gate E1 checklist (organizer, 2026-09-09)** — backend side: [x] E1-T01…T07, T09, T10, T11, T13 verified · [x] `bin/e1-smoke` green locally (magic link, cookie sessions, lockout, impersonation, handoff, OIDC, Learn dry-run) · [ ] staging smoke + real SendGrid N-25 (needs E0-T13: SSH/DNS from Jordi) · [ ] E1-T08 Learn adapter + `POST /platform/accounts` / `PUT /accounts/{id}/password` implemented (currently 501; needs the Laravel repo) · [ ] web E1-W07 (cookie mode) + E1-W04 (front integration) verified. E2 work continues in parallel; the gate closes when the four open boxes are ticked.

Added 09-09: **E1-T14** CI publishes the api image to GHCR + `docker-compose.consumer.yml` (web integration E1-W04 and staging run the published image).

## E2 · Census and catalogs (thread A) — opens after gate E0 (front with mocks; integration needs E1)
Planned tasks: E2-T01 contracts (S02-B, S03, S05, S14) · E2-T02 parameters API (`/parameters*`, `/club*`, holidays, postal codes) · E2-T03 levels, rings, FAQ + `CapacityCalculator` · E2-T04 team and roles · E2-T05 plans and prices · E2-T06 census domain + endpoints (members, dogs, family groups, documents, booking block) · E2-T07 universal list + saved views + sync exports · E2-T08 async export engine · E2-T09 audit queries · E2-T10 Playoff mapping + census importer (dry-run on anonymised fixtures) · E2-T11 seeds (Cànic catalogs + `demo-seed` 184 members / 242 dogs) · E2-T12 club pages (`ClubPage`: rules, privacy, image consent, welcome guide; added 09-09). · E2-T13 CI stability: bounded export size check (added 09-09 after the first red CI post-E2-T06).

## E3 → E12 (summary; details in `docs/PLA_DESENVOLUPAMENT.md` and the Catalan backlog `docs/backlog` when synced)
- **E3** Public signup + dashboard (S04, S14 WP-E) — gate: a fictional signup enters from the public form, is validated in D2, the member receives the welcome and logs in; D1 shows KPIs.
- **E4** Planning + activities (S06, S07) — gate: a week generated from templates and validated; a class with bookings cancelled with events.
- **E5** Bookings + free training + scheduler framework (S08, S09, S15 P1/P6/P7/P9) — gate: full booking/cancel/waitlist/training cycle in staging with jobs running; k6 peak test passes; zero overbooking under concurrency.
- **E6** Attendance + follow-up (S10, S15 P3/P8).
- **E7** Communications (S11: engine, templates, SMS Twilio, push, email bounces, mass communications; S15 P4).
- **E8** Billing, payments (SEPA pain.008, Stripe, manual), packs, inactivity and leave (S12, S13, S15 P5/P10, S18 WP-C) — gate: simulated + generated remittance, XML validated, rollback, Stripe test webhook.
- **E9** Courses and build sessions (S16: port of the web-planner through `CoreApiStore`, Unity export contract) — independent thread from E0.
- **E10** Club console (S17: platform API, D19, on-demand TLS, cloning, support access).
- **E11** Hardening + QA (S14 WP-D RGPD, security review, backups, Lighthouse, E2E, migration rehearsal, guided QA with Josep).
- **E12** Migration and go-live (S18 WP-E: cut-over on a Sunday before 20:00, welcome batches, first supervised remittance).
