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

### Gate E0 (checked by the organizer with Jordi) — **PROMOTED 2026-09-16**: tag `gate/E0` = `061671a`, branch `release` ← `main` (Jordi; Mac health check with `curl -4` passed)
- [x] `docker compose up -d --wait` starts api + mongo; `/api/v1/health` is `UP` — E3-T06 evidence `21-fresh-stack.log` (empty database, with/without `Host`); the 09-09 `500` was a stale native Java listener on `[::1]:8080` (INC-01 closed) → Jordi: kill it and check with `curl -4 -fsS http://127.0.0.1:8080/api/v1/health`.
- [x] `bin/core club:apply seeds/club-canic.yaml` and `seeds/club-minim.yaml` applied; second run reports 0 changes (E0-T10, E2-T11 idempotency; `SEED_PASSWORD` required since E1-T09 — INC-05).
- [x] `/branding` returns the Cànic theme for its host and the AgilityHub theme for the minimal club; unknown host → `404 UNKNOWN_HOST` (E0-T05; re-proven in E3-T06 `21-fresh-stack.log`).
- [x] Password login with `admin@example.test` at the Cànic host → `/me` with `roles [ADMIN]`; same account at the minimal host → `403 NO_MEMBERSHIP` (E0-T09/E1 ITs, `bin/e1-smoke`).
- [x] CI green with T-02-01/02/03/04/06/07/12, tenant-repository test, `RequiresModuleIT`, `AuditContractTest`, `OutboxIT`, `IdempotencyIT`, `RateLimitIT`, message parity (`061671a`: 349 unit + 430 IT).
- [x] `docs/openapi/openapi.json` committed and the CI diff proven (E0-T12; snapshot job in `ci.yml`).
- [~] Staging answers (`/health`) and one backup has been restored — **deferred to the release** (A31, 24-09: everything is proven on the local Docker stack until the release; E0-T13 is re-scoped into E11-T04 (deploy assets provable locally) + E12-T01 (release deployment)); not a blocker for the tag (organizer 26-09).
- [x] Playbooks written — `docs/playbooks/` (E0-T14, verified 06-09); the backup/restore playbook lands with E11-T04 (organizer 26-09).

## E1 · AgilityHub ID (thread C) — OPENED 2026-09-06 (E1-T01 ready; the rest open in order as their dependencies are verified; E0-T13 staging deferred until Jordi provides SSH/DNS)
Decisions needed first: A1 (applied), A2, A3, A4 of `docs/DECISIONS_PENDENTS.md`; SendGrid account.
Planned tasks: E1-T01 contract S01 · E1-T02 identity core (magic link, sliding refresh + rotation, lockout, `/me/*`, SecurityEvent) · E1-T03 email base with SendGrid (S11 WP-11-B0: N-25/26/27) · E1-T04 impersonation + handoff · E1-T05 OIDC provider (discovery, PKCE, userinfo, JWKS rotation, seed clients) · E1-T07 onboarding «Completa el teu perfil» (`Account.onboardingPending`) · E1-T08 Learn import CLI (dry-run, bcrypt hashes untouched) · E1-T09 Learn adapter PR (Laravel repo, not deployed) · E1-T10 integration + gate. (Front counterparts live in the web repo.)

Added 06-09: **E1-T11** OpenAPI required-by-default for DTO schemas (springdoc customizer + contract test) — from the web E1-W06 finding on the branding schemas.

Added 09-09: **E1-T13** A1 cookie delivery of the refresh token for browser clients (`tokenDelivery = COOKIE`) + A3 platform-roles API/CLI; DEPLOY proxy recipe.

**Gate E1 checklist (organizer, 2026-09-09)** — backend side: [x] E1-T01…T07, T09, T10, T11, T13 verified · [x] `bin/e1-smoke` green locally (magic link, cookie sessions, lockout, impersonation, handoff, OIDC, Learn dry-run) · [~] staging smoke + real SendGrid N-25 — deferred to the release (A31) → E12-T01 (organizer 26-09) · [~] E1-T08 Learn adapter + `POST /platform/accounts` / `PUT /accounts/{id}/password` — post-R1 by A30 (prepared, not linked; organizer 26-09) · [x] web E1-W07 (cookie mode) + E1-W04 (front integration) verified (09-09). **GATE E1: passed with deferrals** (organizer 26-09: the two remaining boxes are release/post-R1 items, not gate blockers).

Added 09-09: **E1-T14** CI publishes the api image to GHCR + `docker-compose.consumer.yml` (web integration E1-W04 and staging run the published image).

## E2 · Census and catalogs (thread A) — opens after gate E0 (front with mocks; integration needs E1)
Planned tasks: E2-T01 contracts (S02-B, S03, S05, S14) · E2-T02 parameters API (`/parameters*`, `/club*`, holidays, postal codes) · E2-T03 levels, rings, FAQ + `CapacityCalculator` · E2-T04 team and roles · E2-T05 plans and prices · E2-T06 census domain + endpoints (members, dogs, family groups, documents, booking block) · E2-T07 universal list + saved views + sync exports · E2-T08 async export engine · E2-T09 audit queries · E2-T10 Playoff mapping + census importer (dry-run on anonymised fixtures) · E2-T11 seeds (Cànic catalogs + `demo-seed` 184 members / 242 dogs) · E2-T12 club pages (`ClubPage`: rules, privacy, image consent, welcome guide; added 09-09). · E2-T13 CI stability: bounded export size check (added 09-09 after the first red CI post-E2-T06).

### Gate E2 (back) — checked by the organizer 09-09 23:20
- [x] Census complete (members, dogs, family groups, documents/attachments, booking block, roles), universal lists with saved views and sync/async exports; INSTRUCTOR projection without financial fields (E2-T06/T07/T08).
- [x] Catalogs (levels, rings, FAQ, plans/prices, team) and parameters generated from the catalog with `lastChange`; club pages (E2-T02…T05, T09, T12).
- [x] `migration:playoff --dry-run` on anonymised fixtures: report without errors, no real data in the repo (E2-T10).
- [x] `club:apply` + `seed:demo` idempotent: 184 active / 194 members, 242 dogs (E2-T11) — local stack; the staging load waits for E0-T13.
- [x] D5/D15 < 500 ms with two filters on the demo seed — measured by E2-W07 (verified 19-09): 45.5–57.7 ms on the local stack with the 184-member seed. **GATE E2: passed** (organizer 26-09).

## E3 · Public signup + dashboard (thread A) — task files installed 09-09 (`not_open`; the organizer opens E3-T01 at gate E2)
Planned tasks: E3-T01 contract S04 (`/signup*`, `/checkout-sessions`, `/me/dogs/signup`, `/members/{id}/signup|validation|rejection`, virtual list fields) + dashboard schema check against S14 §6 · E3-T02 signup domain (id documents/phones/postal codes per country profile, `FirstMonthCalculator`, `UpfrontAllocator`, `FamilyHolderMatcher`, `SignupPlanCatalog`, upfront lines, state machines; T-04-01…10) · E3-T03 signup endpoints (public flow, identity checks, uploads, family lookups, D2 validation/rejection, add-dog, `PaymentProvider` + `FakeCheckoutGateway`, rate limits, N-01/02/03/37/39, seeds; T-04-11…28) · E3-T04 dashboard back (`DashboardQuery` + cache/invalidation, `RiskCardBuilder` over S06 ports, `DogActivityQuery`, `/dashboard`, `/dashboard/counters`; T-14-01…06, 11, 22, 23) · E3-T05 integration (`bin/e3-smoke`, D1 seed values, DEPLOY, gate checklist).

Added 09-09 (night): **E3-T06** hardening of INC-01…04 + INC-06 (health independent of tenant/data, 500s logged, truthful compose healthcheck, `MONGO_PORT`, `scope` always present) — order 5, so it runs right after E3-T01; it unblocks the gate E0 promotion.

### Gate E3 (back — checked by the organizer 2026-09-16; E3-T01…T06 verified — **reopened 24-09 by the gate audit**)
- [x] `bin/e3-smoke` green twice on the local stack (E3-T05 evidence `10-smoke-second.log`, `11-smoke-image.log`): public signup (family group found, SEPA without IBAN → warning) → D1 pending → D2 validation → N-02 in the mailbox → welcome link → `/me`; add-dog → N-37; rejection → N-03; `signup.enabled=false` → `422 SIGNUP_CLOSED`. — **reopened 24-09**: red on `main` since E4-T05. The «future verticals» assertion is stale (E3-T07 logs `03`/`04`) → E3-T08 step 1. — **organizer 26-09: green twice again** at `e663872` (E3-T17 round 2, logs `23`/`24`), after E3-T11's re-run at `d791361` (logs `03`/`04`).
- [x] `GET /dashboard` with the seed: `pendingSignups` 3 (1 older than the warn days), `activeMembers` 184, class/training blocks `null`/0 (ports until E4/E5), `dogsByLevel` 242 over 8 levels (E3-T04/E3-T05). — **reopened 24-09**: the class block is live since E4-T05, and the chart changes with E35 → E3-T11 re-states the values. — **organizer 26-09:** E3-T11 log `07` at `d791361`: `pendingSignups` 3 (1 older than `warnDays` 2), `activeMembers` 184, `classOccupancy` per R-14-03 over the ACTIVE/FINISHED sessions of the seeded week (49 seats when seeded on a Friday), `trainingBookings` 0/0, `dogsByLevel` 242 over the 8 progression levels `CAD`, `A`–`G`, with `others` 0 (E35). The smoke re-checks the D1 counters at `e663872`.
- [x] CI green at `2d0423c` (360 unit + 469 IT); OpenAPI snapshot `2d0423c` staged for the web (E3-W02 adopted `83069c1`; E3-W03 adopts `2d0423c`). — **re-checked 26-09:** CI green at `e663872`; since then the snapshot changed only by E3-T17's descriptions and documented 409s (with a changelog entry).
- [x] Gate audit 24-09 (`roadmap/reviews/gate-E3/consolidated.md`: fail, 1 blocker and 21 majors across both repos): E3-T08, E3-T09 and E3-T10 verified, and the E3-T11 re-run green. — **organizer 26-09:** E3-T08…T10 and E3-T12…T17 verified; E3-T11 verified (the re-run at `d791361`), and E3-T17 completed audit items 1, 3 and 7 (the reused dog of a readmission).

Added 24-09 (Jordi: an exhaustive review of the E3 gate before the stage closes): **E3-T07**, the audit run on the current `main` (clean verify, spec-test traceability, `bin/e3-smoke` ×2, seeds ×2, snapshot drift; no product change). Codex and the organizer audit the code separately.

Added 24-09 (the gate audit's result: **fail**, see `roadmap/reviews/gate-E3/`): E3-T07 is verified as the pre-fix baseline. The fixes are **E3-T08** (signup texts and instructions, a quote per plan, assignable plans, upfront rows per submission, the admins' N-01, D1 freshness, §3 constraints, pending dogs on 13, the smoke), **E3-T09** (signed S3 uploads, rate limits behind the proxy, readmission without overwriting — E38 — and the security minors) and **E3-T10** (the other minors and the weak tests). **E3-T11** re-runs the audit; the organizer opens it.

## E4 · Planning and activities (thread A) — OPENED 2026-09-16 (E4-T01 ready; the rest open in order as their dependencies are verified)
Decisions in force: A21 (activities: type enum + label, drafts do not block rings, publishing does with a conflict dialog, FIFO waitlist, N-32d), A22 (no Sunday classes at R1, physical deletion of template bands/classes with audit, instructor reads D3/D4, single automatic description form, the student never sees counts on 10), B18, B20 (`activities.cancelDeadline = EVENT_START`). Catalog amendments of 16-09: `CATALEG_ERRORS.md` (`DUPLICATE_*` 409; `ACTIVITY_FULL`, `WEEK_ALREADY_GENERATED`, `BAND_NOT_EMPTY` explicit 409; the other S06/S07 business codes 422), S14 R-14-09 (`TEMPLATE_BAND_DELETED`, `ACTIVITY_UPDATED`, `ACTIVITY_REGISTERED_BY_CLUB`, `ACTIVITY_REGISTRATION_CANCELLED_BY_CLUB`).
Planned tasks: E4-T01 contracts S06 + S07 (OpenAPI forms A–D / A–C, documents + indexes, status audit, purposes, 501 stubs) · E4-T02 planning domain (templates, `DescriptionResolver`, `InconsistencyDetector`, `CoverageCalculator`, `WeekGenerationUseCase`, P2 endpoints) · E4-T03 calendar, classes, cancellation, ring blocks, day grids (ports with null-objects for E5/E6) · E4-T04 activities (lifecycle, registrations with FIFO promotion, public API, consumers, N-32a…d) · E4-T05 integration (demo seed with templates A/B + Saturdays, validated week, block, inconsistency, 4 activities; `bin/e4-smoke`; gate evidence).

### Gate E4 (back — checked by the organizer)
- [x] `bin/e4-smoke` green twice on the local stack: week generated from «Setmana A» + «Dissabtes» (holiday skipped, inconsistent template refused with `TEMPLATE_INCONSISTENT`) → validated as a whole → the Wednesday 18:50 class with 4 fictional registrants cancelled in one transaction (`ClassCancelledByClub` in the outbox, N-08a APP/EMAIL rows + SMS intents) → an activity published blocks its ring (conflict dialog lists the class) and is listed for the member; public API answers with the key and refuses without it. — **organizer 26-09:** E4-T05 verified 24-09 (`bin/e4-smoke` ×2 in `roadmap/evidence/E4-T05/`: cancellation of the Wednesday class with its registrants, `ClassCancelledByClub` + N-08a rows, activity publication `409 → {cancelClasses} 200`, public API with/without key).
- [x] Day grids: `view=member` without counts; `view=instructor` with counts and cancelled classes dimmed; coverage table per level with the D3 vocabulary. — **organizer 26-09:** T-06-28 on the real core (E4-W05, verified 26-09): `view=member` without counts, `view=instructor` with counts; coverage vocabulary per E4-W01/E4-W06.
- [x] CI green (`e663872`); OpenAPI snapshot `d791361` adopted by E4-W05; E4-T06 read models adopted by E4-W05/E4-W12. **GATE E4 (back): passed** (organizer 26-09) — the front gate closes with E4-W13…W16.

## E5 · E6 · E7 · E8 — INSTALLED 2026-09-19 (organizer-less mode: all tasks `ready`, chained by `depends_on`; the queue advances on `awaiting_verification` deps)
E5 bookings + training + job framework (E5-T01 contracts + `JobCatalog`/`Job`/`SchedulerTick`, T02 bookings/seat holds, T03 waitlist, T04 free training + ring blocks, T05 jobs P1/P2/P6/P7/P9 + N-54, T06 `/me/home` + seed + `bin/e5-smoke` + k6) · E6 attendance + follow-up (T01 contract, T02 attendance sheet + day/week queries + PDF, T03 tasks/attachments/follow-up, T04 jobs P3/P8 + seed + `bin/e6-smoke`) · E7 communications, thread C (T01 contract + `NotificationCatalog`, T02 engine + Twilio/VAPID with fake senders when credentials are absent, T03 templates/log/preferences, T04 P4 reminders + announcements + matrix test + `bin/e7-smoke`) · E8 billing (T01 contracts S12+S13, T02 invoicing/simulation/run/rollback, T03 SEPA pain.008, T04 Stripe/providers, T05 packs/inactivity/leave, T06 jobs P5/P10 + billing importer + accounting export + `bin/e8-smoke`). Catalog amendments 19-09: `INACTIVITY_NOT_APPLICABLE` (422), S14 audit actions `JOB_TRIGGERED`, `PACK_ADJUSTED`, `INVOICE_CREATED_MANUAL`, `REMITTANCE_SUBMITTED`, `CARD_CHARGES_STARTED`. Gates E5–E8: the integration task of each stage pastes the gate evidence in its report; the organizer ticks the checklists below.

Added 26-09 (global audit of E0–E5 and of the E5–E8 task files; `docs/INCIDENCIES_OBERTES.md` v1.4, `docs/DECISIONS_PENDENTS.md` v2.0, rulings E41–E52): **E5-T27** and **E5-T28** (corrections pulled forward: dual-role member routes, impersonation `launchUrl`, password recovery, keyed PUT/DELETE idempotency, health with Mongo, test-clock guard, P2 retry; SEPA PATCH keeping the IBAN, white-label sender name, mandate references, rejection with an open checkout, the week create-vs-validate race, chip normalisation, D1 on a pending readmission) run after E5-T26 (E5-T23…E5-T26 were added by the organizer during the same day); E6-T02 depends on E5-T27 and E8-T01 on E5-T28. E7-T01 no longer waits for E6-T04 (E7-T02 does), so thread C can run E7 alongside E6/E8.

Added 27-09 (verifications of E5-T26…E7-T01 and of the web's E5-W01…E5-W03, rulings E61–E67): **E5-T29** (the back office's contract gaps found by the web — the registrants' `displayState` and level, the staff waitlist's «{guia} + {gos}», a training booking's end and member number, a class booking's description and ring, a block's ring, `filter-values` for three lists — and `nextBookableAt` as the start of the next booking week) runs after E5-T28; the web adopts it in E5-W05.

Added 30-09 (verification of E7-T02's round 2, ruling E72): **E7-T05** (notification engine hardening: an accepted send survives a failed settlement and a restart, bounce suppression at every e-mail attempt, a push expiry that is never lost, a monthly SMS counter that never goes back, the web's e-mail routes, and the dispatcher's failure-path table). E7-T04 now depends on it.

Added 30-09, afternoon (verification of the web's E6-W02, ruling E74): **E6-T05** (`HistoryItem.activityId`, so that screen 25 links an activity row to the activity's page) runs after E5-T28; the web adopts it in E6-W04 step 0b.

Added 30-09, evening (verifications of E5-T28's round 2, E5-T29 and the web's E6-W03, ruling E75): **E5-T30** (signup checkout hardening: a lost answer's retry sends the provider exactly the first request, a late completion needs every row still pending, a released partial row keeps its payment history, and the checkout's failure-path table) runs after E5-T28; **E6-T06** (`GET /followup/filter-values`, a follow-up list that searches, `InstructorWeek.trainingSlotMinutes`) runs after E6-T05; the web adopts it in E6-W04 step 0c.

Added 30-09, night (verifications of E5-T29's round 2, E7-T03 and the web's E7-W01, ruling E76): E7-T03's round 2 also publishes `GET /members/{id}/notification-preferences` (D10's block) and the details of the messaging errors (D9).

Added 30-09, night (A35 closed by Jordi, ruling E78): a **third loop** (Codex) works in a second clone of this repo (`agilityhub-core-api-c`) and takes only **thread D**; the main loop takes threads A, B and C. Each clone lists its threads in an untracked `.roadmap-threads` file that `check.py --next` reads. Thread D starts with **E11-T04** (deploy assets proven locally, `ready`); E11-T03, E9 and E10 follow as thread D.

### Gate E5 (back — organizer 26-09, from E5-T06's verification; k6 on the real server and T-15-30 deferred to the release, A31/E28)
- [x] `bin/e5-smoke` green twice on the local stack with P1/P6/P7/P9 running: book → cancel in time and late → waitlist join → seat released → claim → training slot booked and cancelled (E5-T06 logs, steps 1–12).
- [x] `bin/e5-perf` within the E28 targets: peak flow p95 485 ms (target 800), holds 249 ms (target 500), `last_seat` 1×201 + 49×409 — zero overbooking; lanes-off proofs in E5-T07.
- [x] E5-T01…T20 verified; CI green at `924303e`…`be2f4a8`.
- [ ] E5-T21…E5-T30 verified, CI green, snapshot staged for the web (E4-W13…W18, E5-W01…W05). — organizer 30-09: E5-T21…E5-T29 verified; E5-T30 open.
- [ ] Front: E5-W04 (T-08-40, T-09-40 on the real core with the E5 seed and the moved clock).

### Gate E6 (back — organizer)
- [ ] `bin/e6-smoke` green twice: the sheet from 21 and from D12 (same `PUT`, replayed with the same `Idempotency-Key`), «ha avisat» frees the seat and notifies the waitlist (N-15), a no-show → N-19 once (P3), `class-finishing` at +15 min (P8), the history states on 25.
- [ ] Tasks with attachments readable by the member and the instructor; D14 unread per account; the week-agenda PDF.
- [ ] E6-T01…T06 verified, CI green, snapshot staged (E6-W01…W04). — organizer 30-09: E6-T01…T05 verified; E6-T06 open.

### Gate E7 (back — organizer; real SMS and push on devices are release items, A31)
- [ ] The channel × audience × preference matrix test green for every R1 code (counts in E7-T04's report).
- [ ] An announcement to 10 fictional members with log + `ANNOUNCEMENT_SENT` audit; a template edited at D9 reflected in the next notice in each recipient's language; P4 reminders at the configured lead.
- [ ] Legacy SMS/PUSH intents converted to `SKIPPED_STALE`; `SMS_ALLOWED_NUMBERS` guard proven outside `prod`.
- [ ] E7-T01…T05 verified, CI green, snapshot staged (E7-W01…W03).

### Gate E8 (back — organizer; bank acceptance of the pain.008, the bookkeeper's acceptance of the accounting export and daily backups are release items, @jordi)
- [ ] `bin/e8-smoke` green twice: simulation → run → XSD-valid XML equal to the golden file → mark returned → rollback → re-run with the same numbers; both `billing.cashInvoicing` branches and both `collectionDayOfMonth` semantics tested (A28/A29).
- [ ] Stripe test charge + signed webhook, or the fake path with the line marked blocked (@jordi keys).
- [ ] Pack consumed / returned / expired, inactivity and leave visible on the receipt; the plan change with the 40 % discount; the «Inactivitats i baixes» badge counts.
- [ ] Accounting export produced (R-12-26 columns); the billing importer reconciled on the anonymised fixtures.
- [ ] E8-T01…T06 verified, CI green, snapshot staged (E8-W01…W04).

## E9 → E12 (summary; details in `docs/PLA_DESENVOLUPAMENT.md`; the Catalan backlog lives in the source folder `05-desenvolupament/backlog/`, not in the repo — the 26-09 review proposes the E9–E12 task files in `backlog/revisio-26-09/REVISIO_GLOBAL_26-09.md`)
- **E5** Bookings + free training + scheduler framework (S08, S09, S15 P1/P6/P7/P9) — gate: full booking/cancel/waitlist/training cycle on the local Docker stack with jobs running (staging only at release: A31, 24-09); k6 peak test passes; zero overbooking under concurrency.
- **E6** Attendance + follow-up (S10, S15 P3/P8).
- **E7** Communications (S11: engine, templates, SMS Twilio, push, email bounces, mass communications; S15 P4).
- **E8** Billing, payments (SEPA pain.008, Stripe, manual), packs, inactivity and leave (S12, S13, S15 P5/P10, S18 WP-C) — gate: simulated + generated remittance, XML validated, rollback, Stripe test webhook.
- **E9** Courses and build sessions (S16: port of the web-planner through `CoreApiStore`, Unity export contract) — independent thread from E0.
- **E10** Club console (S17: platform API, D19, on-demand TLS, cloning, support access).
- **E11** Hardening + QA (S14 WP-D RGPD, security review, backups, Lighthouse, E2E, migration rehearsal, guided QA with Josep).
- **E12** Migration and go-live (S18 WP-E: cut-over on a Sunday before 20:00, welcome batches, first supervised remittance).

### E9–E12 per-stage task lists (organizer 30-09; from the global review of 26-09, `backlog/revisio-26-09/REVISIO_GLOBAL_26-09.md` §5; the task files are written and installed before E8 closes)
Pattern per stage: contract → domain → endpoints and integrations → processes, seed, smoke and gate. Sizes: S ≤ ½ session · M = 1 · L = 1–2 · XL = 2–3. Web tasks are listed in the web repo's `ROADMAP.md`.

**E9 · Courses and build sessions (S16) — thread D.** Organizer prep: done 30-09 (ruling E77: one placement engine, `course-core` on the client, whose `critical` warnings need an ADMIN's `force`; `courses.defaultWarningThresholdM` and `courses.buildSessionMaxHours`; the catalog rows E9-T01 adds with the code; the §14.2 model; three new routes). A6 is closed: the planner's Supabase holds test data only, so nothing is imported (Jordi 30-09).
- **E9-T01** · Contract S16: courses, ring geometry, marker sheets, setups, calibrations, placements, ring setups, build sessions (with export and join), obstacle inventories, `/platform/courses`, `/challenges` as 501; documents, indexes and the JSON-Schema validation of `normalizedJson` · deps: web E9-W01, E2-T03, E4-T03, E5-T04, E8-T01 · L
- **E9-T02** · Courses library and ring geometry (R-16-01…04, 12, 14, 16, 17) · E9-T01 · L
- **E9-T03** · Placements, ring setups and their integrations: S08's `RingSetupPort`, S06 `placementId`, S07 placements, N-31, P5f (R-16-05…09, 13, 15) · E9-T02, E8-T06 · L
- **E9-T04** · Live build sessions: SSE, join code, `BuildSessionExportV1` (R-16-11) · E9-T03 · M · cut #2
- **E9-T05** · E9 integration: demo seed, `bin/e9-smoke`, gate; no Supabase import (A6: test data only) · E9-T03 (E9-T04) · M

**E10 · Club console (S17) — thread D.** The first cut if time runs short; `club:apply`, the domain check with `/internal/domains/allowed`, support access and INC-49 are kept.
- **E10-T01** · Contract S17 WP-B · E1-T13, E2-T12, E8-T04 · M
- **E10-T02** · Platform API (R-17-02…13) · E10-T01 · XL · cut #1, except domains, support access and the public key
- **E10-T03** · E10 integration: `bin/e10-smoke` «a second club in under an hour», P9's domain re-check, DEPLOY «alta d'un domini» · E10-T02 · M

**E11 · Hardening and QA (S14 WP-D, PLA §11).** E11-T01…T04 are never cut.
- **E11-T01** · RGPD, S14 WP-D: data package and N-50, erasure and N-52, `ErasureExecutor`, the monthly `RetentionSweep` (INC-10 included), consents, platform views (R-14-14…17) · E8-T05, E8-T06, E7-T02 · XL
- **E11-T02** · The correction pass: every open api incidence of `INCIDENCIES_OBERTES.md`, INC-36 first · E8-T06 · L
- **E11-T03** (thread D) · Security review and hardening: rate limits, headers, CORS per club, the image's secret scan, dependency audit, PITest, T-15-30, a compose smoke in CI, structured logs, Mongo timeouts · E8-T06 · L
- **E11-T04** · Deploy assets provable locally (E0-T13 part 1): `deploy/compose.prod.yaml`, backups and a verified restore, `DEPLOY.md` as a runbook · no dependency · L · **thread D, installed 30-09 (`ready`)**
- **E11-T05** · Migration rehearsal (S18 WP-D) on the anonymised derivative of Josep's fresh export · E8-T06 and the export · M
- **E11-T06** · The E11 gate run: clean verify, traceability in CI, every smoke, seeds twice, snapshot drift · E11-T01…T05 · M

**E12 · Migration and go-live (S18 WP-E).**
- **E12-T01** · Release deployment: droplet, Caddy, real secrets, SendGrid, Twilio, VAPID, the Stripe test webhook, the backup cron and one restore on the server, the k6 re-measure. Needs SSH and DNS by 21-10 · E11-T04, E11-T06 · L
- **E12-T02** · Cut-over: the D-7 rehearsal, the legal texts, Playoff read-only at D-1, D0 on a Sunday before 20:00, reconciliation ≤ 1 %, welcome batches, DNS, the first week, jobs on, D+7 support · E12-T01, E11-T05 · M

**Out of R1:** S19 (the Learn adapter, E1-T08: A30 leaves it without a date) and S20 (the AR/VR spike).
