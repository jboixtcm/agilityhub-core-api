# Club definitions

Build the current source with `./mvnw -q package`, then run:

```sh
export SEED_PASSWORD='<choose a local test password>'
bin/core club:apply seeds/club-canic.yaml --dry-run
bin/core club:apply seeds/club-canic.yaml
bin/core club:export canic
bin/core club:apply seeds/club-minim.yaml
bin/core club:apply seeds/club-template-default.yaml
bin/core identity:seed-test-accounts --club=canic
```

`bin/core` builds the jar if it is missing. Rebuild explicitly after changing Java or
resources. Commands use the application's MongoDB environment variables and exit
with status 0 or 1. They start no HTTP server and disable background scheduling.
The Cànic definition contains 2 admins, 3 instructors and 10 members; the minimal
definition contains 1 admin and 2 members. All are fictional `@example.test`
accounts. Their `${SEED_PASSWORD}` references require that environment variable,
which has no default and is never printed. Omit `accounts[].password` to create
passwordless accounts. Passwords are accepted in local/test; staging/prod require
both an environment reference and `--allow-seed-passwords`. Literal passwords are
local/test only and must never be committed.

`identity:seed-test-accounts --club=canic` applies only the accounts section of
`seeds/club-canic.yaml` to an existing club. A definition path can replace the
`--club` option. The alias supports `--dry-run` and `--allow-seed-passwords` and
preserves club configuration.

The schema is JSON Schema 2020-12 and is also packaged in the application jar.
Localization is inside `club`, as specified by E0-T10. Use either `preset` or
`modules`. Only declared parameter overrides are changed; omitted overrides,
scoped overrides, and existing accounts are preserved. Account names, locales,
passwords and onboarding flags initialize new global accounts only; applying a
second club never resets them. `accounts[].roles` sets the exact tenant membership
roles, preserving status and member/instructor links. The legacy `admins[]`
section creates passwordless accounts and adds ADMIN while preserving other roles.
An email cannot appear twice across these sections. Provider names
are stored as configuration slots; existing configuration remains intact. This E0
format accepts provider names only, so credential values cannot enter a seed.

Diffs show added/changed/unchanged sections and the before/after fields. A dry run
performs no aggregate, parameter, identity, audit, or outbox writes. Infrastructure
startup may create collections and indexes. Reapplying unchanged input writes
nothing. Changed applies persist the club, parameters, accounts, memberships, `CLUB_UPDATED`
audit summary (`source: APPLY`), and outbox events in one Mongo transaction.

Local/test definitions trust their domains so local branding can be exercised.
Staging/prod domains are PENDING until the domain-verification vertical is
implemented. A template cannot declare domains. The template seed is not an
operational club.

Catalogs are applied transactionally with the club definition. The schema closes each
catalog shape. Levels and plans match by `code`, rings by `shortName`, FAQ by a stable
seed `code` (stored separately from its editable question), and prices by
`planCode` + `concept` + `validFrom`. Omitted entries and fields are preserved; exports
include current catalogs and all price history. Editing an existing price obeys the
normal immutable-history rules. Only the first price of an unused plan can bootstrap
historical validity (the seed uses 2026-01-01); subsequent prices use ordinary date
and overlap validation. Team rows require census members and belong to `seed:demo`;
`catalogs.instructors` must be empty. Message templates remain deferred to E7.

The Cànic has nine levels (including TER), five rings, five plans/current prices,
and seven active FAQ entries. All FAQ answers retain the provisional marker. D8
supplies the amounts; the EUR 10 therapy maintenance price and zero tax are the
provisional S05 §12 assumptions. Only enabled ca/es locales are stored in catalogs;
pages keep their existing ca/es/en placeholder translations. The consumer variant
has identical content with local routing hosts.

```sh
bin/core seed:demo --club=canic --seed=42
```

Demo generation requires local/test, an applied club definition and empty census
collections. `seeds/demo-canic.yaml` supplies all club-specific counts, names, catalog
codes and a fixed business reference date. The default seed is 42. It creates 184
ACTIVE, 3 PENDING, 4 INACTIVE and 3 LEFT members (194 total), 242 ACTIVE dogs,
12 two-person families, 3 instructors and 2 admins. Six dogs have downloadable
fictional PDF documents; the remaining vaccination documents are pending. The first
15 members link to the existing seed login accounts, preserving passwords and roles;
new `@example.test` accounts are passwordless. IBANs use fictional bank/branch 0000
and valid domestic/mod-97 check digits. No real data is read and no welcome mail is sent.
Member numbers are reserved for later signups.

Generation is deterministic for the same seed/specification/tenant; census IDs stay
stable. Infrastructure timestamps and account/attachment IDs are allocated when
persisting. A completed run makes subsequent runs no-ops and preserves manual demo
edits. A changed seed/specification or preexisting census is rejected with
`CLUB_NOT_EMPTY`; use a fresh disposable database for another dataset. Do not remove
the completion record to reset a live demo. Local document files use the configured
attachment directory; as with normal uploads, a failed transaction can leave an
unclaimed local file. Use a disposable attachment directory for test rehearsals.

To reproduce D1's active-member count, use
`GET /api/v1/members?size=20&filter=status:eq:ACTIVE` and inspect `totalItems` (184).
The universal list contract rejects size=1; an unfiltered list includes all 194
members. D1's occupancy and training counts (142/163, 56/38) need the later
scheduling/training fixtures and are not fabricated in this census seed.

Export includes account metadata and membership roles, excluding passwords and hashes.

Every demo member also gets one fictional mobile number: the club country's prefix
(`+34` for ES) and `phoneNumberFormat` over the member number (`600000001`…), so
cancellation SMS intents can be exercised. Numbers are fixture values; SMS stay
`QUEUED` locally (no provider is configured).

### E4 planning and activities (`planning`, `bookings`, `activities` sections)

`seed:demo` continues after the census with the dated E4 demo, created only through
the S06/S07 application services (templates, generation, validation, ring blocks,
class cancellation, activity lifecycle and registrations) in one transaction, as the
first demo admin (`administrators[0]`) and, for registrations, as each member. Dates
are relative to `--week-start=YYYY-MM-DD` (an ISO Monday), by default the club-local
Monday of the **current week (W)** of the run; `--week-start` is meant for tests. The
first completed planning run is recorded per club (`demo_seed_runs`, id
`<clubId>:planning`); later runs report `0 changes (demo planning, week start …)`
whatever their week start, and a changed seed/section is rejected with
`CLUB_NOT_EMPTY` (use a fresh disposable database). Registrant choice is deterministic
for seed 42 (member-number order, shuffled with the seed); the 15 members linked to
the seed login accounts (`admin@…`, `instructor@…`, `member@…` …) are never
registered, so front tests can register them.

Arrangement agreed with the organizer (MESSAGES 2026-09-19); fronts select rows by
state, never by date or first name:

| Week | State | What it holds (screens) |
|---|---|---|
| W (current) | untouched | no planning data (nothing can be generated or blocked in the past) |
| W+1 | `GENERATED`, 38 `DRAFT` classes from «Setmana A» + «Dissabtes» | D4b «Esborrany» filter, validation card (`draftCount = 38`, `canValidate = true`) |
| W+2 | `VALIDATED`, classes `ACTIVE` | D4/D4c/D7 data below |
| W+3 | not generated | `GET /weeks/generation-candidates` proposes it (`proposed = true`) |

- Templates (D3): «Setmana A» (`WEEKDAYS`, clean, 32 classes), «Setmana B» (copy of A
  + a second Central class on Wednesday 20:00 → one `RING_DOUBLE_BOOKED`,
  `canGenerate = false`), «Dissabtes» (`SATURDAY`, clean, 6 classes). Bands: R-06-01
  08:30–09:30 · 09:30–10:30 · 16:30–17:30 · 17:40–18:40 · 18:50–19:50 · 20:00–21:00;
  Saturdays 08:30 · 09:30 · 16:30 · 18:30 (**deviation**: template bands may not
  overlap, so R-06-01's Saturday 17:40 band is omitted and 18:30 holds the tournament
  conflict). Descriptions are automatic («A+B», «B+C», «C+D+E», «F+G», «Cadells»)
  except the manual «D i sup.» (automatic «D i sup.» would need TER too), «Obed.
  urbana» (Wednesday 20:00), «Teràpia» and «Particular» (capacity 1). Instructors are
  the three E2-T11 demo instructors, rotated.
- W+2 (D4): Monday 08:30 Central A+B `4/5`, Monday 08:30 Muntanya C+D+E `3/4`,
  Wednesday 08:30 Central A+B `5/5 +1`, Thursday 18:50 Cadells `2/5`, «Teràpia» and
  «Particular» `1/1`; **Wednesday 18:50 Central B+C: 4 registrants (2 `paidWithPack`)
  + 2 waiting** — the R-06-10 / D4c case («ANUL·LA I AVISA ELS 4 ALUMNES»);
  Wednesday 09:30 Cadells `CANCELLED{RISK_REVIEW}` with the `scheduling.autoCancel.text`
  message; Carretera `BLOCK`/`MAINTENANCE` Wednesday 16:00–18:00, note «manteniment»
  (no class on Carretera then); a loose «Particular» on Petita, Thursday 18:50, created
  after validation with the Cadells instructor → D4 warning `INSTRUCTOR_DOUBLE_BOOKED`.
- Class registrants use the **demo bookings adapter** (`DemoClassBookings`, collection
  `demo_class_bookings`, `local`/`test` profiles only, `@ConditionalOnMissingBean`):
  it implements `ClassBookingsPort` and writes counters through
  `ClassSessionService.bookingCounters` (the S08 writer path: class version check,
  ACTIVE recheck); counters are never set by hand. **E5 replaces it** with the real
  S08 `bookings`/waitlist, and its adapter wins automatically.
- Activities (D7), all through `ActivityService`/`ActivityLifecycleService`/
  `ActivityRegistrationService` (`origin = APP`):
  «Torneig d'Estiu 2026» (`COMPETITION`, Saturday of W+2, 18:30–20:30, five rings,
  40 places, waitlist on, registration until the day before, 22 `ACTIVE`) published
  with `cancelClasses` → the W+2 Saturday 18:30 Central class (2 registrants) becomes
  `CANCELLED{ACTIVITY}` with N-08a, five `ACTIVITY` blocks; «Seminari de handling»
  (`SEMINAR`, Saturday of W+5, 09:00–13:00, Central, **full: 12 + 2 waitlisted** —
  **deviation** from the mockup's «6/12», a waitlist needs a full activity);
  «Lliga social — 3a jornada» (`SOCIAL_LEAGUE`, Saturday of W+6, 09:00–14:00, five
  rings, no maximum → «obertes», 10 registrants, a 1×1 PNG image and a «Fictional demo
  document» PDF through the signed-upload path); «Demostració Festa Major»
  (`DEMONSTRATION`, Sunday of W+8, no hours, `atClub = false`, fictional location,
  `DRAFT`). Tournament activity blocks exist from the seed on, so W+2 is the only
  validated week with a Saturday conflict; generating W+5/W+6 later would meet the
  Seminari/Lliga blocks (validation reports `RING_BLOCKED`).

`bin/e4-smoke` (see `docs/DEPLOY.md`) exercises this seed on a disposable stack.

The Cànic parameter catalog values are all product defaults, so its seed has an
empty override map. Its theme comes from the approved `01-acces.html` tokens.
The generic AgilityHub theme is a neutral blue/light seed preset; all example
administrators and contact addresses are fictional `@example.test` addresses.
