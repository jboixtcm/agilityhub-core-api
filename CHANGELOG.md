# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Fixed

- E5-T11: follow-ups of the E5-T08 review, and ruling E29.
  - R-08-13, N-46 gate: the N-15 consumer records the delivered offer on the waitlist entry (`offerNotifiedAt`) with
    a conditional update on `{state: NOTIFIED, notifiedAt}`, in the transaction of the N-15 rows. N-46 compares
    `offerNotifiedAt` with `notifiedAt` and no longer queries `notifications` (`appSentSince`/`appRowSince` removed).
    A seat taken before the N-15 consumer runs now gets neither a late N-15 nor an N-46. The three row-only
    notification writers (`appOnce`, `smsIntentOnce`, `pushIntentOnce`) join the caller's transaction.
  - `notifications.N-46.held` ignores a `SeatHoldReleased` without a live booking of its dog (a released hold): no
    demotion and no N-46.
  - `CensusRepository.save` fails fast (`IllegalStateException`) when a `@ForeignOwned` field differs from the value
    it was read with; `ForeignOwnedSnapshots` records those values when an entity is read.
  - S07 promotion: payload and envelope origin `SYSTEM` (`DomainEvent.Origin.SYSTEM`, no actor), whoever's request
    freed the seat.
  - Keyed public routes (plans, club pages, activities): the key is checked before the club; an unknown slug is 403
    `INVALID_API_KEY`, never 404 `CLUB_NOT_FOUND`.

- E5-T07 round 2: the organizer's six points.
  - Integration tests: the `BookingFixtures.impersonating:126 NoSuchElement` CI flake. `DemoScenarioSeedIT` and
    `DemoPlanningSeedIT` emptied every collection, `signing_keys` included, so a Spring context cached before them
    could no longer sign (`SigningKeys.ring()`). They now use `AbstractIntegrationTest.wipeDatabaseKeepingBootstrap()`,
    which keeps the bootstrap-owned collections (`signing_keys`). No production change.
  - R-09-13 serialisation: one document per ring and training grid slot (`ring_slot_locks`) replaces `ring_day_locks`,
    and the `day:` training lane is dropped. With the lanes off, 20 bookings on 20 different slots of one ring-day
    ended 15 × `409 STALE_VERSION` on the ring-day document; they now all commit. A booking touches its slot; a ring
    block, a class moved onto a ring or an activity block touches every grid slot its range overlaps
    (`TrainingConflictPort.lockSlots`, S09 `TrainingSlotLocks` computes the grid over any number of days).
  - R-09-13, S05 side: a ring deactivated or no longer open to free training touches every slot of its booking
    window (`RingTrainingBookings.lockBookableSlots`), so it conflicts in Mongo with a concurrent booking of the ring.
  - S06 `SchedulingTransactions` waits 50–150 ms (jittered) between its 6 attempts, like the other contexts.
  - `cancelMatching` (S07) takes the lanes of live registrations' activities only.
- E5-T06 round 2: the organizer's seven points.
  - Caches: `shared.application.CacheLoads` is now a per-cache wrapper with a generation counter. A lock-free load
    that overlaps an invalidation drops the value it stored, so a module toggle, a parameter change or a domain
    change can no longer leave the old `ClubConfig` or host lookup (negative lookups included) cached for 5 min.
    `CacheInvalidationRaceIT` reproduces the race (4 failures before the fix) and `CacheLoadsTest` covers the helper.
  - k6 (ruling E28): new fictional load-test club `seeds/club-perf.yaml` + `demo-perf.yaml` (330 members, empty
    5-seat classes). `bin/e5-perf` runs the gated peak with 300 distinct members within 5 s on 10 empty classes (all
    50 seats booked), last seat 50 at once, and the burst (correctness only). RESULT lines give the distinct
    members, the seats filled and the answer histogram. `perf/e5-seat-holds.js` asserts every error code, confirms
    with `Idempotency-Key` = `seatHoldId` and runs exactly one iteration per member.
  - Aggregates: with `ACTIVITIES` off, `/me/bookable-classes.activities` is `null` (nullable schema, S08 §9).
    `/me/home` rows carry `dogName` only with «Tots». The labels of the CLASS and CLASS_WAITLIST rows come from one
    batched class query (`ClassSessionBookingAccess.labels(ids, locale)`).
  - `BookingContext.asOf` may be called only by `Demo*` seed classes (ArchUnit `BOOKING_TIME_OVERRIDE`).
  - The seat-hold pre-check leaves a `CLASS_FULL{heldOnly}` to the locked path (holds may expire in the meantime).
  - `seeds/README.md` gives the exact seed and `POST /test/clock` commands for the web T-08-40. `bin/e5-smoke`'s
    `start()` takes its seed list.

- E5-T10: round-2 review follow-ups (E5-T01, E5-T02, E5-T05), ruling E30 and the platform purge.
  - Jobs: the R-15-22 clock rule also forbids `now(ZoneId)` and `Calendar.getInstance()`. A holder whose lease renewal
    fails applies no further item and closes as a lost lease. The reaper's `FAILED` is counted in
    `jobs.run.duration`. The WARN of a reaped holder carries its local counters.
  - PAY_TO_BOOK: a failed provider call cancels the booking only if it is still `PAYMENT_PENDING`, then abandons the
    checkout and rethrows the original failure. The idempotency key is always released
    (`IdempotentOperation.release()`). The provider must be enabled for the club (`422 PAYMENT_PROVIDER_NOT_ENABLED`).
    `checkoutUrl` is kept on `Booking.charge` while `PAYMENT_PENDING`, so `GET /bookings/{id}` shows it again. P7 also
    expires the timed-out booking's checkout session and line.
  - E30: a booking cancelled because the provider call failed carries `checkoutFailed: true` in `BookingCancelled` and
    sends no N-40. The P7 timeout still sends N-40.
  - D1: the risk rows come from one batched read per collection (`activeBookingsByClass`, `clubCancelledByClass`,
    `bookings`, `people`, `dogs`). The D1 labels (the three languages, the no-ring text, the club's default locale)
    are built by `SessionProjection`. `bin/e5-smoke` asserts that `GET /dashboard .riskReview` equals
    `GET /risk-review`.
  - N-17 stores `class_description` and `ring_name` (its catalog row since 24-09). The notification contract fixture
    covers N-16 (both audiences), N-17, N-33 and N-54.
  - P9: a platform pass purges the `domain_events` and `job_runs` without `clubId` (catalog retention, the last five
    real executions per process kept), once per UTC day, claimed by the first real P9 run of the day.

- E5-T09: review follow-ups for S09/S15/S06 and the seeds (E4-T05, E5-T01, E5-T04, E5-T05 reviews) and two organizer
  rulings of 2026-09-24.
  - Errors: `JOB_UNKNOWN` is 404, `SLOT_NOT_ON_GRID` 400 and `OVERRIDE_NOT_ALLOWED` 403 (`ErrorCode` and §1 of
    `CATALEG_ERRORS.md`; they were 422 by rule 0).
  - S05 rings follow S09 R-09-13 (R-05-08 amended): turning `allowsFreeTraining` off, or deactivating a ring, with
    live training bookings answers `422 RING_HAS_BOOKINGS{bookings[]}`; `PATCH /rings/{id}` with `cancelBookings: true`
    cancels them (`CANCELLED_BY_CLUB` / `RING_NOT_RESERVABLE`, N-47) in the same transaction. `409 RING_IN_USE` stays
    for future live classes.
  - S15: `jobs.tick.overrun` counts the minute ticks a tick lasting past the next one made the scheduler skip (not
    same-minute contention); a long tick renews and then settles its lease. A dry run takes no lease (R-15-08) and a
    dead dry run is reaped after the lease time. P1 `week-opening` drops the grid caches itself, with or without
    `FREE_TRAINING`.
  - S09: the MEMBER occupancy carries no block id (the day grid de-duplicates blocks by ring and time); the
    impersonating admin's late cancellation has no end (R-09-10/R-09-16), also at `now ≥ endsAt`.
  - S08: `ClassSessionBookingAccess.counters` refuses to raise `booked` above the capacity (`CLASS_FULL`).
  - ArchUnit: only `Demo*` seed classes call `DemoSeedActor`; only `clubs.bookings`/`clubs.scheduling` write the class
    counters; the consumer envelopes (`*ForeignEvent`) are no longer `DomainEvent`s, so they cannot be published.
  - Seeds: `seed:demo --reanchor` re-anchors the planning weeks (and their registrants) to the run date on a
    long-lived stack, once per week; `seeds/README.md` says that the demo phone numbers are real-format and that SMS
    must never be sent from a non-production stack.
- E5-T08: review follow-ups for S07/S08 (E4-T04, E5-T02, E5-T03 reviews).
  - S07: a FIFO promotion publishes `ActivityRegistrationChanged{origin: SYSTEM, promoted: true}` (N-32b APP only),
    whatever the promoted registration's origin. The public activities API checks `X-Api-Key` before the club and the
    module: without a valid key every slug answers `403 INVALID_API_KEY` (an unknown slug too, instead of
    `404 CLUB_NOT_FOUND`).
  - Census: `Member.lastDogForClass` / `lastDogForTraining` are `@ForeignOwned`. Bookings, training bookings and the
    census consumers write them without touching `Member.version` (the entity-class `updateFirst` bumped `@Version` by
    itself; `trainingSeq` increments did too), and census saves never rewrite them. An admin editing a member no
    longer gets `STALE_VERSION` because the member booked meanwhile.
  - S08: a same-class swap never raises `ClassBelowMinimum`. `cancelFutureByMember(memberId, by, reason)` takes the
    actor. `GET /bookings` reads a page with one `$in` per collection. Booking events are stamped with the business
    instant (`occurredAt` = `bookedAt` in «as of» seeds; `seeds/README.md`).
  - S08 waiting list: the N-46 consumers return before any write or class lock unless the class has offered entries,
    send N-46 only to entries whose N-15 of that offer was delivered, and also run on the confirmation's
    `SeatHoldReleased`, so a PAY_TO_BOOK booking that takes the last seat notifies when the seat is taken. The claim of
    a demoted entry answers `SEAT_TAKEN` only while the class is full (as the hold). `counters.waiting` is recounted when
    WAITLIST is switched back on (`ClubModulesChanged`).
  - API: `WaitlistEntry.position` is `null` (and no longer required) when `waitlist.mode = ALL_AT_ONCE`; the web must
    regenerate its types.

### Added

- E5-T11 / E29: `Level.progression` (S05 §3; default `true`, levels stored before read `true`; the Cànic seed sets
  Teràpia to `false`). The automatic «{first} i sup.» of S06 R-06-03 counts only the active progression levels.
- E5-T07: concurrency guarantees proven on Mongo, and deterministic outbox dispatch in the integration tests.
  - `shared.application.LocalLanes` replaces the three copies of 256 hashed `ReentrantLock` lanes in
    `ActivityTransactions`, `BookingTransactions` and `TrainingTransactions`. It keeps one fair lock per aggregate
    key (`activity:`, `class:`, `dog:`, `member:`, `slot:`, `day:`, prefixed with the open tenant), never one per
    tenant, taken in key order. The infrastructure property `core.concurrency.local-lanes` (default `true`) switches
    it. `application.yml` and `docs/DEPLOY.md` record the single-instance assumption (ADR-003).
  - `shared.application.TransactionRetries`: the meters `core.transactions.retries{context,cause}` and
    `core.transactions.exhausted{context}` for the retried S07/S08/S09 transactions. The retry budgets are unchanged.
  - S09: every training booking now `$inc`s `Dog.trainingSeq` whatever `bookings.limitUnit` is, so a shared dog
    cannot be booked on two rings at once (review E5-T04 #2). The booking and the S06 writes that check the ring's
    live bookings (ring block create/patch, activity blocks, class moved onto a ring) share a ring-day sequence in
    the new technical collection `ring_day_locks` (R-09-13, review E5-T04 #3). Training lanes add `day:`.
  - Mongo-path ITs with the lanes off: `ActivityConcurrencyIT` (T-07-24/25), `BookingLanesOffIT` (T-08-29/31/32)
    and `TrainingLanesOffIT` (T-09-28/32/33, the dog and ring-day conflicts). Each proves its mechanism with a held
    transaction and the retry meter.
  - Integration tests: `AbstractIntegrationTest` discards the PENDING outbox backlog before each test. The claim is
    global, oldest first and 100 per `dispatch()` call; earlier tests left up to 724 PENDING records, which starved
    a test's own events depending on class order. The T-07-23 Awaitility loop is removed; `OutboxIT` reproduces the
    starvation. `ActivityFixtures` is extracted from `ActivityIT`; `support.ConcurrencySupport` is new.

- E5-T06: S08 aggregates (WP-08-D), the E5 demo scenario, `bin/e5-smoke` and the k6 evidence (WP-08-G, WP-09-F, back
  half of WP-15-E).
  - `GET /me/home` (03, `MemberHomeQuery`): the chips (own dogs, plus the family group's with FAMILY_GROUP), the
    R-08-02 counters of W0/W1 summed over the filtered dogs, and one chronological list of future rows from class
    bookings, waiting-list entries, free training (`MemberTrainingRowsPort`, adapter in `clubs.training`) and activity
    registrations (`MemberActivityRowsPort`, adapter in `clubs.activities`). It also returns the R-08-20 instructor
    visibility, `history.monthsVisible`, `notifications.unreadCount` (in-app rows without `readAt`) and the
    impersonation actor. Module off removes its rows (§9).
  - `GET /me/bookable-classes` (04, `BookableClassesQuery`): exactly one dog (requested, `lastDogForClass`, or the
    first own dog), the pack card (`PackCard` state), the single-class terms and price, the booking-block banner and
    the activities block. Classes come from W0…W2, level-admitted, minus the ones already booked or waited for, with the
    R-08-03 state through `BookableRow` over `BookingEligibility` and `BookingLimits`. The class list and counters come
    from `BookableClassesCache` (30 s); the per-dog state is live.
  - Demo seed: the current week is validated. The E4 waiting registrants join through `WaitlistService.join`. The new
    `scenario` section (applied with a future `--week-start`) books, cancels, joins, trains, blocks and exempts
    through the real services «as of» scenario instants. It adds `seeds/club-fifo.yaml` + `seeds/demo-fifo.yaml` (a
    fictional FIFO + PAY_TO_BOOK club, so P6 and P7 have work). `DemoMembers`, `DemoScenarioSeeder` and
    `DemoTrainingSeeder` are new; `DemoPlanningSeeder` creates the instructor ring reservations; `DemoActivitySeeder`
    adds the scenario registrations.
  - `bin/e5-smoke [--image]` (gate E5 on a disposable stack, with the scheduler on and the test clock moved), and
    `bin/e5-perf` + `perf/e5-seat-holds.js` (k6 peak and last seat, zero-overbooking check).
  - Messages `bookings.home.classTitle` / `trainingTitle` in ca/es/en.
  - Changed: `HomeMember.gender` is optional and nullable (OpenAPI). `TrainingContext.now()` reads
    `BookingContext.now()`, which is identical outside the demo seed. `DemoSeedStep.Input` carries the census member
    ids and the run date. The Dockerfile ships the FIFO club seeds. `E5ContractIT` treats the two aggregates as served.

- E5-T05: S15 scheduled processes of E5 (WP-15-C + the api half of WP-15-B).
  - P1 `week-opening` (R-15-11): one transaction per opening; it invalidates the config cache, warms the S08
    `BookableClassesCache` (W0…W2, 30 s, key `{clubId}:{W0 key}`), sets `Week.openedAt` and emits `WeekOpened` and, with
    FREE_TRAINING, `TrainingCounterReset` (S09 drops its grid cache). N-33 goes out right away, or later from the
    `WeekValidated` consumer. APP rows use one `insertMany` per 500 (`NotificationFanout`); PUSH intents go in batches
    of 100 on their own pool.
  - P2 `risk-review` (R-15-12): the counted dogs are recounted in the item transaction; today's under-strength
    classes are cancelled through S06 (`ClassAutoCancelled`, N-17 to the admins and instructors, N-08a to the
    registrants with the text in each recipient's language); the next days get `ClassAtRisk` → N-16, once per
    booking and once for the admins (`risk` marks). A late run never cancels a class that has started.
  - R-15-12b: N-54 handler of `ClassBelowMinimum`, plus the `WaitlistExpired` re-check.
  - P6 `waitlist-fifo` (R-15-16), P7 `payment-timeouts` (R-15-17), P9 `cleanup` (R-15-19; orphan signup uploads, finished
    exports, processed outbox events, `stripe_events` when present, `job_runs` except the last five per process; the
    TTL collections are only reported).
  - `/jobs*`, `GET /risk-review` (form A, also feeding the S14 D1 card's names) and `/platform/jobs*` served;
    `bin/core jobs:run <route-id> [--club=] [--dry-run]`.
  - Messages `notif.N-16/N-17/N-33/N-54.*` in ca/es/en; `scheduling.autoCancel.text` now takes `{minDogs}`.

- E5-T04: S09 free training (WP-09-B + WP-09-C).
  - `GET /training-slots` (R-09-02/03/04/15): computed grid (never persisted) from opening hours, holidays, DRAFT/ACTIVE
    classes, ACTIVE ring blocks and ACTIVE bookings, with Java DST semantics; MEMBER clipped to the booking window, staff
    ≤ 31 days with `occupants[]`, `block`, `classSession`; `setup` with COURSES through the new `RingSetupPort` (null
    object until S16). Static layer cached per `{clubId, date}` (60 s, invalidated by the `training` consumers); the
    booking path always reads live data.
  - `POST /training-bookings` (R-09-05…09, R-09-16): one Mongo transaction retried whole on DuplicateKey/WriteConflict,
    `trainingSeq` `$inc` per unit, the partial unique seat index as final guard, «Qualsevol» in catalog order, counter by
    session week, impersonation (`BACKOFFICE`, `override.limit`, audit `TRAINING_BOOKED_BY_CLUB`).
  - `POST /training-bookings/{id}/cancellation` (R-09-10): threshold on instants, ADMIN_LATE with a reason for the
    impersonating admin (audit `TRAINING_CANCELLED_BY_CLUB`); system paths `cancelFutureByMember`, `cancelForInactivity`,
    `cancelForDog` (not scheduled here) and `cancelByClub` (R-09-13, `Propagation.MANDATORY`).
  - `GET /me/training-summary`, `GET /me/training-bookings`, `GET /training-bookings/{id}`, the universal list
    `GET /training-bookings` and its ADMIN export.
  - Real `TrainingOccupancyPort`, `TrainingConflictPort` (S06 day grids, class and ring-block conflicts, S07) and
    `TrainingBookingsQuery` (S14 dashboard), registered by `TrainingAutoConfiguration`.
  - Notifications N-06, N-07 and N-47 (APP + EMAIL + SMS intent) from outbox consumers, with `notif.*` keys in ca/es/en.

### Changed

- E5-T05 Round 2: S15 review fixes.
  - S14 D1 risk card = `GET /risk-review`. The dashboard maps the form-A rows of `RiskReviewQuery.rows()`
    (`AUTO_CANCELLED` → `CANCELLED`, `WILL_REVIEW` → `PENDING_DECISION`) through the new `RiskReviewSource` port and no
    longer evaluates risk itself. The `ClassSessionsQuery` and `RiskEvaluator` dashboard ports are removed. The OpenAPI
    is unchanged.
  - P2 `risk-review` neither cancels nor warns about a class that has already started (`skippedStarted`), also with
    `classes.riskAutoCancelSameDay = false`. Form A leaves such classes out.
  - R-15-12b after a FIFO expiry: P6 re-checks the minimum inside its own item transaction. The separate
    `alerts.WaitlistExpired` consumer is removed.
  - N-16: the admins' copy has its own wording (ICU `select` on the new variable `audience` = `STAFF` | `MEMBER`), in
    ca/es/en.
  - P9 `cleanup`: `CleanupRepository` binds every query to the open tenant (`TENANT_MISMATCH` otherwise). The five
    `job_runs` kept per process are the latest real executions, so dry runs and `SKIPPED` rows no longer count.

- E5-T02 Round 2: S08 R-08-18 PAY_TO_BOOK now runs end to end.
  - The booking transaction only prepares the checkout in Mongo: the `SINGLE_CLASS` `UpfrontPayment` line with the new
    `bookingId` (model `UpfrontPayment.bookingId?`) and a `checkout_sessions` row with `bookingId`.
  - The provider checkout opens once, after the commit, with no seat lock held. The 201 with `checkoutUrl` is stored for
    idempotent replay after that.
  - If the provider call fails, the checkout is abandoned and the booking is cancelled at once (`PAYMENT_TIMEOUT`).
  - The provider's completion or expiry (`CheckoutService.complete` / `expire`) emits `UpfrontPaymentSucceeded` /
    `UpfrontPaymentFailed` carrying `bookingId` and `concept`. The booking consumer settles the booking (ACTIVE + N-04,
    or CANCELLED + `SeatReleased` + N-40).
  - The signup flows never list, charge, replace or cancel booking lines, and a booking payment never replaces the
    member's payment method.
  - An impersonated late cancellation is audited as `BOOKING_CANCELLED_LATE` too (S14 R-14-09).
- E5-T01 Round 2: the final write of a job run is conditional (R-15-04/R-15-06). `JobRunRepository.finish` replaces the
  row only while it is still `RUNNING` under the same lease holder. `SchedulerRun`/`JobFailed` are published only when
  that write closed the row, so a reaper with a stale read and a slow holder whose lease was reaped change nothing. A
  reaped run gives up its claim (`exclusive = false`), so a dead `CATCH_UP` is retaken as `CATCH_UP` on the next tick.
  The R-15-22 ArchUnit rule also forbids `Clock.systemUTC()`, `Clock.systemDefaultZone()`, `Clock.system(zone)` and
  `new Date()` outside `ClockConfiguration`.
- E5-T04: `MongoUsageCounter` counts `training_bookings.state = ACTIVE` (was the provisional `status`); the export of
  `/training-bookings` is ADMIN only (S14 R-14-12, the shared `ExportPolicy`); the APP notification variables keep
  `time` and `has_admin_text`; the idempotency filter runs `/training-bookings` and its cancellation in their own
  retried transaction and replays business 409/422 like S08.

### Added

- E5-T03: S08 class waiting list (WP-08-C).
  - `POST /waitlist-entries` (R-08-12): the class must be full by bookings alone, then the booking eligibility
    chain, then `waitlist.maxPerClass`, `maxPerDogPerWeek` / `maxPerDogPerWeekIfAttended` (per owner with
    `limitUnit = MEMBER`), and the seat must be acceptable. `position = max + 1`; writes `lastDogForClass`.
  - Reads: `GET /waitlist-entries/{id}` and `GET /class-sessions/{id}/waitlist-entries`.
  - Leaving: `POST /waitlist-entries/{id}/cancellation` sets `MEMBER` or `ADMIN` and emits `WaitlistLeft`.
  - Claim: `POST /waitlist-entries/{id}/claim` runs the R-08-08 confirmation transaction (swap, BR-01). A hold
    through a taken offer answers `SEAT_TAKEN`, an expired FIFO offer `WAITLIST_OFFER_EXPIRED`.
  - Offers: the `SeatReleased` consumer notifies every entry (`ALL_AT_ONCE`) or one per free seat (`FIFO`, with
    `confirmBy`). The `WaitlistExpired` consumer calls `offerNext`. Both are idempotent by state.
  - Demotion: in `ALL_AT_ONCE`, the booking that takes the last seat sends the other NOTIFIED entries back to
    ACTIVE, inside the same transaction.
  - Consolidation: a direct booking consolidates the dog's entry (`BOOKED_DIRECTLY`).
  - Silent cancellations: `WaitlistTransitions.cancelAll` (S06), and `WaitlistService.sweepStarted` (for E6's P8,
    not scheduled here) and `cancelByMember` (`MEMBER_LEFT`, S15 §13 proposal).
  - Notifications, only from outbox consumers: N-15 (APP + SMS intent + PUSH intent, action `CLAIM_SEAT`) and
    N-46 (APP).
  - `counters.waiting` is kept in each transaction and is 0 with WAITLIST off.
  - The `ClassSessionUpdated` consumer also refreshes the class start and week copied onto live entries.
- E5-T02: S08 class bookings (WP-08-B). Booking weeks from `bookings.weekOpensAt` in club-local
  time (DST-safe, no weekday literal), weekly limits by dog or owner with swappable / not
  selectable bookings, one ordered eligibility pipeline (dog, member, block, leaving, inactivity,
  level, class, week, pack), `POST /seat-holds` in one Mongo transaction serialised by
  `seat_locks` (retry ≤ 3, in-process lanes, live holds only), `DELETE /seat-holds/{id}`,
  `POST /bookings` with the atomic swap, PAY_TO_BOOK / CHARGE_ON_ATTENDANCE (SINGLE_CLASS),
  idempotent replay including stored 409/422, `GET /bookings/{id}` (`displayState`, R-08-20
  instructor visibility), `GET /me/bookings`, `GET /bookings` (universal list),
  `GET /class-sessions/{id}/bookings`, HMAC-signed `.ics`, cancellation in time / late at
  `bookings.lateCancelThresholdMinutes` (240) with `SeatReleased{notifyWaitlist}` (strictly
  above 30 min), `ClassBelowMinimum` guarded by `risk.lowAlertSentAt`, instructor «ha avisat»,
  system cancellations, audit `BOOKING_CREATED_BY_CLUB` / `BOOKING_CANCELLED_BY_CLUB` /
  `BOOKING_CANCELLED_LATE`, outbox consumers N-04 / N-05 / N-36 (APP + EMAIL + SMS intent) /
  N-40 and `ClassSessionUpdated` / `UpfrontPayment*` consumers. Real `ClassBookingsPort`
  (`cancelAllByClub` also cancels live waiting-list entries) and `BookingActivity` adapters;
  ports with null-object defaults for E6/E8/E5-T03 (`AttendanceStatePort`, `PackBalancePort`,
  `InactivityPort`, `WaitlistConsolidationPort`) plus local/test pack and inactivity stand-ins;
  `SingleClassChargePort` over `payments.application`. New env var `BOOKING_CALENDAR_KEY`.
- E5-T01: contract of S08 (bookings, seat holds, waiting list), S09 (free training) and S15
  (scheduled processes): 32 guarded `501` operations, wire forms, error `details` schemas and
  the `bookings`, `seat_holds` (TTL), `waitlist_entries`, `seat_locks`, `training_bookings`
  (partial unique active-seat guard), `job_runs` and `job_locks` documents with their indexes;
  `Week.openedAt/openingNotifiedAt`. Process framework in `platform.application.jobs`:
  `JobCatalog` (ten R-15-01 rows), `Job {plan/apply}`, `JobOccurrences` (club-local occurrences,
  DST, catch-up windows), `SchedulerTick` (every minute, `tick` lease), `JobRunner` (switch,
  module, club status, leases with renewal and reaping, one transaction per item, dry run,
  `SchedulerRun`/`JobFailed` on the outbox, Micrometer `jobs.*`), audited manual trigger
  (`JOB_TRIGGERED`), N-42 consumer (one per process and local day), the test-profile
  `TEST_NOOP` job and `POST /api/v1/test/clock` (`test`/`local` only). `BookingEvent`,
  `TrainingEvent` and `SchedulerEvent` envelopes; ArchUnit rule R-15-22 (time from the Clock).
- E4-T05: `seed:demo` adds the E4 planning/activities demo (`--week-start`, default
  the club-local current Monday) through the S06/S07 services in one transaction:
  templates «Setmana A»/«Setmana B» (one `RING_DOUBLE_BOOKED`)/«Dissabtes», week +1
  GENERATED (draft), week +2 VALIDATED with registrants, the R-06-10 class (4 + 2
  waiting), a RISK_REVIEW cancellation, a maintenance block, an
  `INSTRUCTOR_DOUBLE_BOOKED` loose class and the four D7 activities (tournament
  published over a class → `CANCELLED{ACTIVITY}`). New shared `DemoSeedStep` hook,
  local/test `DemoClassBookings` adapter (`demo_class_bookings`, replaced by E5's
  `ClassBookingsPort`), `ClassSessionService.bookingCounters` (S08 counter writer path)
  and fictional member phones (`phoneNumberFormat`). `bin/e4-smoke` rehearses gate
  E4 (back) on a disposable stack; `DemoPlanningSeedIT` covers T-06-28/T-07-32 (back)
  and `DemoBookingsTest` the demo bookings adapter/seeder guards (branch coverage gate).
  No API, parameter, event, notification or error-code change.

### Changed

- E5-T02: the E4-T05 demo bookings adapter and `demo_class_bookings` are removed; `seed:demo`
  books the same registrants through `SeatHoldService` + `BookingConfirmationService` (as of the
  W+2 opening) and waiting registrants become `waitlist_entries`. `ClassSessionService.bookingCounters`
  is replaced by `ClassSessionBookingAccess.counters` (no catalog reference lock inside booking
  transactions). The idempotency filter replays stored 409/422 outcomes of `POST /bookings` and
  the claim route. OpenAPI: only nine operation descriptions change (no longer 501).
- E4-T04 Round 2: Wait for durable impersonation notifications with a bounded
  assertion in T-07-23 instead of assuming notification rows are immediately ready.
  After the organizer removed the backticks from the deferred S14 R-14-09 names,
  full verification passes again (401 unit / 580 integration tests); the OpenAPI
  snapshot is unchanged.

- E4-T05: Record blocked integration-seed requirements: current-week fixtures
  conflict with past-date guards, and the tournament's ACTIVE-class cancellation
  conflicts with keeping its week in GENERATED state. Resolved by the organizer's
  week +1/+2 arrangement (2026-09-19); implemented in the E4-T05 entry under Added.

- E4-T04: Implement activity editing, publication and ring synchronization,
  per-person registration with FIFO promotion, member views and public localized
  activity feeds with signed files. Add audited lifecycle changes, N-32a/b/c/d
  notification delivery, universal lists/exports, system cancellations and the
  finish-ended CLI. Preserve atomic idempotent responses through transaction retries.

- E4-T03: Implement transactional week validation, class edits/cancellation,
  ring blocking and activity synchronization; serve calendar and privacy-aware
  member/instructor day grids. Add replaceable booking/training ports, risk and
  dashboard projections, the finish-ended CLI, audited changes and durable
  localized N-08a/N-08b delivery with SMS intents. Cover rollback, concurrency,
  tenant/role/module variants and the disposable HTTP rehearsal.

- E4-T02: Implement planning template edits, localized automatic descriptions,
  live inconsistencies and coverage, and transactional draft week generation
  with holidays, club-local time, concurrency protection and idempotent replay.
  Add audited physical deletions, catalog reference locks, cache invalidation,
  universal week lists and tenant/role/domain/integration tests.

- E4-T01: Publish 31 scheduling and 24 activity contract operations with typed
  OpenAPI projections and guarded 501 responses; add tenant-scoped Mongo documents
  and indexes, final scheduling usage projections, activity upload purposes,
  catalog event/notification fixtures, and security/privacy/persistence tests.

- E3-T05: Add the one-command Compose signup/dashboard gate (`bin/e3-smoke`,
  including image mode), reviewable fictional pending signup fixtures with
  one overdue missing-account warning, and the E3 deployment/browser checklist.

- E3-T04: Serve the admin dashboard and menu counters from tenant-scoped census
  aggregations with club-local calendar boundaries, cached locale variants and
  outbox invalidation. Add shared dog activity and replaceable scheduling,
  booking and follow-up ports, risk/pending builders, tenant/role tests, and
  nullable occupancy plus optional billing-warning contract corrections.

- E3-T03: Implement public signup, signed documents, recognition, tenant-scoped
  encrypted replay, review/edit/validation/rejection, and authenticated add-dog.
  Persist census consent history, atomic member numbers, identity membership and
  upfront payments with the outbox; deliver N-01/02/03/37/39 through SYSTEM mail.
  Add the local/test checkout gateway, rate limits, explicit signup seeds, amended
  OpenAPI choices and checkout ownership, and a disposable curl/mailbox rehearsal.

- E3-T06: Keep public health independent of tenant data, preserve framework error
  statuses, and log unexpected failures with their response trace ID. Release
  idempotency keys and roll back writes on handled 5xx responses. Add explicit
  Compose healthchecks, configurable MONGO_PORT, and always serialize token scope.
  Document the Mac IPv4 gate and cover health, error, rollback and token regressions.

- E3-T02: Add pure signup contact, postal lookup, first-month and upfront-payment
  rules, family matching and fare proposals, consent renewal and state transitions.
  Reuse country profiles and catalog prices, preserve census document normalization,
  and add ca/es/en gender-aware signup messages with S04 unit and architecture tests.

- E3-T01: Publish eleven S04 signup, checkout, add-dog and validation contract
  stubs with tenant/role/module guards, typed request/response schemas and fictional
  fixtures. Reserve signup list fields, align dashboard enums and nullable blocks,
  and correct signup error mappings to the closed catalog's canonical status rule.

- E2-T11: Apply and export typed club catalogs with idempotent references, audited
  updates and protected price history. Seed the Cànic levels, rings, plans, prices,
  provisional FAQ and cleaned page text. Add the deterministic local demo census
  (184 active members, 242 dogs), family/team/document fixtures and repeat-run guards.
  Adopt the approved MIGRATION_APPLIED audit action with migration counters and
  update its public contract.

- E2-T10: Add the versioned Playoff census mapping, documented input schema,
  deterministic anonymizer and fictional incident fixtures. Import members,
  dogs, explicit families and identity memberships through an atomic tenant-scoped
  apply with encrypted bank details, cutover mandates, outbox/audit and idempotent
  source IDs. Preview reports write nothing; unresolved mappings remain warnings.

- E2-T13: Count export admission on tenant/base-field matches with a maxRows + 1
  bound, skipping census enrichment while preserving joined/computed filters.
  Keep exact rendered totals and cover 5,000/5,001 and 100,000/100,001 boundaries,
  selected IDs, tenant isolation and timed oversized rejection with an unordered bulk fixture.
  Align the existing audit contract assertion with S14's approved ONBOARDING_COMPLETED entry.

- E2-T09: Implement tenant-scoped admin audit lists, member impersonation history,
  filters, detail reads and synchronous/background XLSX/PDF exports with nested
  sensitive-value masking and localized column labels. Verify parameter/level
  lastChange summaries and exhaustive audit-action coverage; document object
  storage deployment and publish the updated OpenAPI contract.

- E2-T12: Add tenant-scoped club pages, publication history, limited Markdown validation,
  admin editing, member/public reads, transactional audit/outbox, and idempotent
  draft-text seeds through club:apply. Publish the OpenAPI contract.

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
