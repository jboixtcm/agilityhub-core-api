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

**These are real-format numbers.** `+34 600 000 001`… is a valid Spanish mobile range that a real person may own, so
**an SMS must never be sent from a non-production stack** (local, test, staging, the smoke and demo stacks): never
configure an SMS provider there. The seed and `bin/e4-smoke`/`bin/e5-smoke` queue SMS intents (N-08a, N-47…) on
purpose; only the missing provider keeps them `QUEUED`. E7-T02 adds the `SMS_ALLOWED_NUMBERS` allow-list, which is the
only way a non-production stack may ever dispatch an SMS, and only to the listed numbers.

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
| W (current) | `VALIDATED` since E5-T06 (remaining days only) | the E5 screens' bookable classes; see the E5 section below |
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
- Class registrants are **real S08 bookings** (E5-T02; the E4-T05 demo adapter and its
  `demo_class_bookings` collection are gone): each booked registrant holds a seat and
  confirms it as its own member through `SeatHoldService` + `BookingConfirmationService`
  (`origin = APP`, one `BookingCreated` + `SeatHeld` + `SeatHoldReleased` each, N-04 queued
  when the outbox runs). W+2 is not bookable yet on the run date (R-08-01: a booking week
  opens one week before it starts), so the seed books **as of the instant that week opens**
  (or the run date when later): `bookedAt` may be a few days ahead of the run. The
  `withPack` registrants get a 10-session pack in the local/test pack stand-in (S12 is E8)
  and their booking carries `packMovementId`. Waiting registrants are `ACTIVE`
  `waitlist_entries` (FIFO `position`) until E5-T03 ships the join service. Counters
  (`booked`, `waiting`) follow the bookings and entries; they are never set by hand.
  Cancelling the class (D4c) turns the 4 bookings into `CANCELLED_BY_CLUB` and the 2 entries
  into `CANCELLED{CLASS_CANCELLED}`.
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

#### Refreshing the planning on a long-lived stack (`--reanchor`, E5-T09)

The dated demo ages: on a stack that lives for weeks (staging demos), W+1 «Esborrany» becomes the current or a past week
and the W+2 D4/D4c classes pass, after which their cancellation fails the past-date guards. Front test runs should still
use a fresh disposable project; for a long-lived demo stack, re-anchor the planning to the run date:

```sh
bin/core seed:demo --club=canic --seed=42 --reanchor
```

- It needs the first `seed:demo` run of the club (same seed and seed file, else `CLUB_NOT_EMPTY`; without one,
  `NOT_FOUND`). The anchor is the club-local Monday of the run date (`--week-start` overrides it, for tests).
- It applies the `planning.weeks` rows to the new anchor, reusing the D3 templates of the first run by name. A week that
  already exists (generated or validated) is **kept as it is**: a week is never generated twice, so after a re-anchor of
  one week the old W+2 (validated) is the new W+1, not a draft. Only the weeks the run generated get the dated
  `looseClasses`, `ringBlocks` and `riskCancellations`, and the `bookings` rows of an ACTIVE class of those weeks
  without registrants get their registrants (real S08 bookings, as in the first run).
- The activities (D7) and the E5 `scenario` are **not** re-anchored: they keep the dates of the first run.
- It is recorded per anchor (`demo_seed_runs`, id `<clubId>:planning:<weekStart>`), so running it again on the same
  week reports `0 changes`; a re-anchor on the first run's own week start changes nothing either.

### E5 bookings, waiting lists, free training and processes (`scenario` section, E5-T06)

E5 changes three things in the E4 arrangement above:

- **W (current week)** is now generated from «Setmana A» + «Dissabtes» and `VALIDATED`, so screens 03/04 have
  bookable classes. Generation skips the days already past (R-06 `PAST`); on a Sunday nothing is left and the week
  stays unvalidated. W+1 (`DRAFT`, D4b), W+2 (`VALIDATED`, D4/D4c/D7) and the W+3 candidate are unchanged.
- **The E4 demo bookings adapter is retired**: since E5-T02 the W+2 registrants are real S08 bookings, and since E5-T06
  the waiting registrants join through `WaitlistService.join` too. R-08-12 needs a class that is full through its
  bookings, so a row with free seats (the D4c «4/5 + 2») briefly gets its booked count as capacity through the S06
  class edit. The entries then join, and the class gets its capacity and `AUTO`/`MANUAL` mode back. A capacity raise
  releases no seat, so the entries stay `ACTIVE`, as D4c shows them. Nothing writes `bookings`, `seat_holds`,
  `waitlist_entries` or `training_bookings` directly, and no counter is set by hand.
- **The `scenario` section** is applied only when `--week-start` is the run's Monday or a later one (club-local).
  Every day of its anchor week must still be ahead. Without it, `seed:demo` reports the `scenario*` counts as `0`, and
  E4 still gets everything. Use it with a future Monday and set the test clock to `demoNow`, the scenario's Monday at
  07:00 local, with `POST /api/v1/test/clock {"instant": …}` (local/test profiles only):

```sh
bin/core club:apply seeds/club-canic.yaml
bin/core seed:demo --club=canic --seed=42 --week-start=<a future Monday>
bin/core club:apply seeds/club-fifo.yaml
bin/core seed:demo --club=fifo --seed=42 --week-start=<the same Monday>
```

Every scenario action goes through the real services, as the member it belongs to, «as of» a scenario instant.
Bookings, cancellations and waiting-list joins run through `BookingContext.asOf`, and free training through the same
instant in `TrainingContext`. The default instant is the opening of the class's booking week, so the classes of the
anchor week are W0 with limit 2. **Timestamps follow that instant**: `bookedAt`, `createdAt`, `cancelledAt`, the waiting
list's `joinedAt` and the outbox `occurredAt` of the same bookings (E5-T08) can all be ahead of the run date. Only the
outbox delivery (`nextAttemptAt`) uses the real clock. Processes and reminders that compare
`bookedAt` with «now» (S15 P7 `bookings.paymentPendingMinutes`, reminders) must run at the scenario's test clock
(`demoNow` or later), never at the real run date. Blocks, exemptions and overrides go through the S03/S06 services as the demo admin, and
ring reservations as the instructor. The rows use the **seed login accounts** (the E4 rows never do), so front tests
can log in as them. Select them by account and state, never by date or by the generated first names. The table below
lists what each account holds at `demoNow` (anchor week = week 0; ids differ per club):

| Account (census ordinal) | Holds | Screens / smoke step |
|---|---|---|
| `member@` (5), E dog | Mon 08:30 MUN `ACTIVE` · waiting on Thu 17:40 CAR · free training Mon and Tue 11:00 MUN (2/3) · registered to «Torneig d'Estiu 2026»; its CAD dog trains by `freeTrainingOverride` | 03 with all four row types; 04 `BOOKABLE`, `WAITLIST_FULL`, `NOT_YET_OPEN`; 08 at 2/3; smoke: book, cancel in time, `CANCELLED_LATE` < 30 min, training 3/3 + 409 |
| `member.2@` (6), E dog | Mon 08:30 MUN `CANCELLED_LATE` (DONE) + Wed 08:30 MUN `ACTIVE` (swappable) · free training Mon 11:00 CEN (a taken capacity-1 slot for others) | 06 swap (`limit.reached`, 1 swappable, 1 not selectable) |
| `member.3@` (7), E dog | Thu 20:00 CEN `CANCELLED` in time · Mon 08:30 MUN `ACTIVE` (inside 4 h at 07:00) · Mon 09:30 CEN `CANCELLED_LATE` | 04 `WEEKLY_LIMIT_DONE`, 29 informative, 07 green and yellow notes |
| `member.4@` (8), E and CAD dogs | nothing booked; CAD dog without free-training right | 04 `WAITLIST_OPEN` (Thu 17:40 CAR); smoke: join, then hold + claim |
| `member.5@` (9), E dog | Thu 17:40 CAR `ACTIVE` | smoke: in-time cancellation frees the seat, so N-15 goes to every waiting member |
| `member.6@` (10), E dog | Thu 20:00 CEN `ACTIVE` (the class has exactly `classes.minDogs` = 2) | smoke: in-time cancellation, so exactly one N-54 |
| `member.7@` (11) | `bookingBlock` «Quota pendent (fictícia)» | 04 `NOT_BOOKABLE{BLOCKED}` + banner |
| instructor 0 | `RESERVATION`/`PRIVATE_CLASS` on Petita, Tue 11:00–12:00, note «Classe particular (fictícia)» | 24 and the grids |

Classes of week 0 filled with non-login registrants (member-number order, seed 42): Tue 17:40 CAR full + 3 waiting
(`WAITLIST_FULL`); Thu 17:40 CAR full + 1 waiting (+ `member@`, so 2) (`WAITLIST_OPEN`); Thu 20:00 CEN + 1. The S15 P2
fixture for a review on **Tuesday 07:30**: Tue 18:50 CEN with 1 registrant (cancelled that day, N-17 + N-08a), Wed
16:30 CEN with 0 (warned, N-16 to the admins), Tue 20:00 MUN `riskExempt`. P2 cancels every other class of that day
below `classes.minDogs` as well: that is the rule, not seed data.

Live row states at `demoNow` across these accounts: `BOOKABLE`, `WAITLIST_OPEN`, `WAITLIST_FULL`,
`WEEKLY_LIMIT_DONE`, `NOT_YET_OPEN` (the W+2 classes) and `NOT_BOOKABLE`. **Deviations:** there are no `NEXT` rows,
because W+1 stays a draft (E4 D4b). `PACK_EMPTY` and the pack card need S12 packs (E8): the local stand-in lives in
each process's memory, so a seed-time pack is invisible to the API. `T-08-13` covers both on the API. `FULL` needs a
club with `WAITLIST` off.

**Fictional FIFO club** (`seeds/club-fifo.yaml` + `seeds/demo-fifo.yaml`, slug `fifo`, host `fifo.example.test`, not
the Cànic): one ring, one level, levels off, `waitlist.mode = FIFO`, `SINGLE_CLASS` on (plan «Classe única»,
`PAY_TO_BOOK`, 12 €), accounts `fifo.admin@` and `fifo.member@` … `fifo.member.5@`. Tuesday 18:00, capacity 1, is booked by
`fifo.member@`; `.2@`, `.3@` and `.4@` join (FIFO positions 1–3). The booking is cancelled in time on the Sunday at
21:00, so the first entry is `NOTIFIED` until 21:30. `fifo.member.5@` books Wednesday 18:00, which stays
`PAYMENT_PENDING` from the Sunday opening on. At `demoNow` P6 expires the first entry and offers the seat to the
second; P7 cancels the booking with `PAYMENT_TIMEOUT` and N-40.

`bin/e5-smoke` (see `docs/DEPLOY.md`) seeds both clubs on a disposable stack with a `--week-start` 8+ days ahead, and
replays the E5 gate at `demoNow`.

The Cànic parameter catalog values are all product defaults, so its seed has an
empty override map. Its theme comes from the approved `01-acces.html` tokens.
The generic AgilityHub theme is a neutral blue/light seed preset; all example
administrators and contact addresses are fictional `@example.test` addresses.
