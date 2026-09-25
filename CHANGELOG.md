# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

- E3-T14: gate E3 audit fixes (api), the payment methods of a signup.
  - R-04-10: one rule for "enabled". A provider is enabled only with `CLUB.paymentProviders.{provider}.enabled: true`,
    the flag `GET /club` already showed. `GET /signup` offers only those methods, in the configured order. `POST /signup`
    and the D2 `PATCH /members/{id}` refuse any other method with `422 PAYMENT_METHOD_NOT_AVAILABLE`. `configured` does
    not gate the offer. The Stripe checks (`PAYMENT_PROVIDER_NOT_ENABLED`) read the same flag.
  - Seeds: the Cànic seed (and its consumer variant) enables `SEPA_XML` and `MANUAL` without creditor data. The other
    seeds keep the providers they listed, now enabled explicitly. The club definition accepts `paymentProviders` as a
    list of names (keeps the stored flags) or as `{NAME: {enabled}}` (sets them); never credentials. `club:apply` keeps
    the configuration stored outside the file, and the export writes the flags.
  - R-04-19 (web E3-W07 round 2): `GET /members/{id}/signup` returns `paymentMethods: [{type, label, current,
    assignable}]`, the methods D2 may assign. The applicant's current method is always listed; it is marked
    `assignable = false` when its provider has been disabled since. OpenAPI snapshot and `docs/openapi/CHANGELOG.md`
    updated.
  - `bin/e3-smoke` checks that `GET /club`, `GET /signup` and D2 show the same methods.

- E3-T13: gate E3 audit fixes (api, 5/5), the checkout follow-ups of the E3-T10 round-2 review.
  - R-04-06 / R-04-26: the checkout leaves out only the rows a readmission superseded, cut at the member's latest `PUBLIC`
    signup block. An `APP_ADD_DOG` block that the code before E3-T10 left in `Member.signup` is no longer a boundary, so
    an earlier add-dog's unpaid rows stay payable; on such a record the public signup's block is read from its dog.
  - R-04-26: every line sent to the payment provider is described in the locale of the submission it belongs to (an
    add-dog's own block, the public signup, a pending readmission's applicant), not in the member's public-signup locale.
  - No contract change (`UpfrontPayments.Line` carries its `submissionId` internally only).

- E3-T12 round 2: the follow-ups of the E3-T12 review.
  - R-04-20: the recipient-cap decision of each event and notification is persisted when taken
    (`signup_notification_admissions`, `_id` = `eventId:code`, TTL one day, no recipient data), so an admitted event keeps
    its right to send across retries and restarts, and a new event is still counted. `RateLimits.admitOnce`, whose
    decision lived only in the instance's cache, is removed.
  - R-04-15: a first month frozen without its `portion` gets the one its frozen amount charged against the plan's monthly
    price on the submission day, or `null` when the amounts cannot tell; never one from today's split day.
    `SignupFirstMonth.portion` is `FULL` · `HALF` · `null`.
  - Step 5: `upfront.firstMonth` appears in the D2 view and in the dry run only when that answer's own lines have a
    `FIRST_MONTH` row (a submission made while BILLING was off has none).

- E3-T12: gate E3 audit fixes (api, 4/4), the follow-ups of the E3-T09 round-2 review and the D2 first month.
  - R-04-06 c / R-04-26: the checkout of a pending readmission sends the applicant's submitted primary address to the
    payment provider as the customer email; the LEFT record and the account's login email are untouched.
  - R-04-20: the per-recipient cap is admitted once per event and notification (`RateLimits.admitOnce`; round 2 persists
    it), so an outbox delivery retried after a provider failure still sends, and only new events count against the cap.
  - R-04-06 c, S04 §8: `SignupSubmitted` carries `locale`, `dogNames` and `upfrontTotal`, and `SignupRejected` carries
    `locale`; N-01 and N-03 read them from the event, so a later submission that reuses the dog (a readmission) no
    longer changes a queued notification. Older events fall back to the submission block.
  - R-04-27: the anonymous `GET /signup` configuration cache is generation-aware (`CacheLoads`): a load that overlapped
    the eviction of a parameter, plan or club write never stores what it read.
  - R-04-15 (web E3-W07): `SignupUpfrontReview.firstMonth {option, portion, startDate, amountDue}` in `GET
    /members/{id}/signup` (frozen at submission) and in the D2 dry run (recalculated after a plan change). The frozen
    `signup.upfront.firstMonth` now stores its `portion`; an older block derives it (round 2: from its frozen amount).

- E3-T09 round 2: the pending readmission (S04 R-04-06 a–d, organizer 24-09) and the family lookup's name key.
  - R-04-05: the identity check and `POST /signup` also match a pending readmission on the primary address it submitted
    (index `{clubId, readmissionRequest.submitted.contactEmails.email}`), so no second pending application is created.
  - D2 cannot change the identity document of a pending readmission: `409 INVALID_STATE`,
    `details.reason = READMISSION_PENDING`; a rejection restores the record exactly, `idDocument` included.
  - N-01 and N-03 of a readmission go to the applicant: the submitted primary address and name, in `signup.locale`. The
    recipient travels in `SignupSubmitted.applicant` / `SignupRejected.applicant`, so a rejection still delivers N-03.
  - R-04-12: `Dog.nameKey` (trimmed, inner whitespace collapsed), written by every census write and by the Playoff
    migration writer, indexed `{clubId, nameKey}` with the primary-strength collation (`dog_name_key`, replaces
    `name_ci`); the startup runner `dogNameKeyMigration` backfills existing dogs idempotently.
  - R-04-22: the validation keeps the member's `accountId` (no second `Account`, the login email never changes); only a
    member without an account is matched or created by its primary email.
  - R-04-20: the per-recipient mail cap reads `signup.rateLimit.notificationsPerRecipientPerHour` per club (default 3).

- E3-T10: gate E3 audit fixes (api, 3/3), the api minors (`roadmap/reviews/gate-E3/consolidated.md` «Minor», api).
  - Signup: a claim FOUND at the lookup but no longer unique or eligible at submission falls back to
    `NOT_FOUND_PENDING` when `signup.allowFamilyGroupPending` allows it (R-04-12); `ACCOUNT_NOT_PROVIDED` reads
    `ibanLast4` in D2, the census list and D1 (migrated SEPA members, R-04-18); a D2 `paymentMethod` PATCH keeps the
    IBAN, holder, holder tax id and `mandateSignedAt` it does not change (R-04-19); the rejection consumer deactivates
    `payload.dogIds` only (R-04-23); GENERIC `validateIban` runs mod-97.
  - `Member.signup` snapshot (§3): `ipHash`, `userAgent`, `paymentMethodTypeRequested`, the frozen `upfront.firstMonth`,
    `validatedAt/By` and `rejectedAt/By/rejectionReason`. An add-dog submission keeps its own block on its dog and never
    overwrites the public signup.
  - Events: `MembershipChanged` of a validation carries `clubId` and the roles `before`/`after`; every identity event
    records its actor and origin (`IdentityEvents`); `UpfrontPaymentRecorded.memberId`; `DogDocumentPending.trigger`
    (`REGISTRATION`, `FILE_REMOVED`); `MemberValidated.dogs` and `DogRegistered.levelId` from the stored dogs; the
    validation assigns the first level without `DogLevelChanged`.
  - N-02 and N-39 render in `Account.locale` (the signup's as fallback).
  - Review of E3-T08 round 2: the checkout charges the rows of every submission of the member (only superseded
    readmission rows are left out); the `nextInvoiceDate` check uses the frozen first-month start while the plan is
    unchanged; the N-01 copies use the event's `planId` and the submission's own locale and upfront.
  - D1: `deltaThisMonth` subtracts leavers by `leftAt` (R-14-02 amended 24-09); `waitingTotal` is `null` without
    `WAITLIST`; `followUpUnread` is `0` without `TASKS`; `percent` is an integer; `RiskNotified.gender` nullable.
  - `GET /branding` adds `club.legalName` and `club.taxId` (R-02-02 amended 24-09).
  - OpenAPI: the S04 error lists name every code their handler can throw; `S04ErrorContractTest` checks it from the
    bytecode.
  - Seed `club-canic`: Pack 6 «Només un cop» / «Solo una vez»; `theme.colors.onPrimary` `#0B0B0B` (A32, AA 5.9:1).
  - Tests strengthened against their §11 rows: T-04-10, T-04-11, T-04-16, T-04-17, T-04-19, T-04-21, T-04-26, T-04-28,
    T-14-02, T-14-11, T-14-22, T-14-23. The unused keys `signup.welcome` and `signup.imageWarning` are removed.
  - Round 2 (review `E3-T10-20260925-0821-codex`, organizer 25-09):
    - the checkout's payable scope is built from the debtor's rows (`UpfrontPayment.memberId`), not from the dogs the
      member owns now: a dog transferred after an unpaid validation (S03 R-03-14) leaves its rows with the member who
      owes them;
    - the N-03 of an add-dog rejection renders in the locale of the rejected dogs' own submission block (S04 §8);
    - OpenAPI: every nullable schema with an `enum` lists `null` in it (14 schemas; `OpenApiConfiguration`
      `nullableEnumsModule`, checked by `OpenApiNullableEnumContractTest` with a JSON Schema 2020-12 validator);
    - `PATCH /members/{id}` and `PATCH /dogs/{id}` join `S04ErrorContractTest`, and their error lists are completed;
    - T-14-22 reads class occupancy from real `class_sessions` in both clubs through the scheduling adapter (the
      `ClassOccupancyQuery` mock is gone from `DashboardIT`);
    - `MembershipChanged` uses `before`/`after` for every emitter (`TeamMembershipService`, `DemoIdentityService`,
      which also adds `clubId`), as `CATALEG_ESDEVENIMENTS.md` states since 25-09;
    - seed `club-canic`: Pack 10 «Només un cop · després 40% dte. en matrícula» / «Solo una vez · …» (mockup 17).

- E3-T09: gate E3 audit fixes (api, 2/3), security and privacy (`roadmap/reviews/gate-E3/consolidated.md` M16–M18).
  - M16: `POST /signup/upload-urls` returns the signed `headers` (`Content-Type`, `If-None-Match: *`); an IT against an
    S3-compatible store (LocalStack S3, signature validation on) proves 403 without them and 412 on a second PUT.
  - M17: `staging`/`prod` refuse to start without `TRUSTED_PROXY_PATTERN` (`TrustedProxyConfiguration`); the limiter
    matches the decoded, normalised path (`identity-%63hecks`, `//` variants); `docs/DEPLOY.md` documents the variable
    next to the Caddy block (`native`, not `framework`), and `s3:DeleteObject` on `signup/` for the orphan cleanup.
  - M18 (E38): a readmission no longer overwrites the LEFT record: the submitted person, contacts, address, payment
    method and consent entries wait in `Member.readmissionRequest`; D2 gets `readmission{current, submitted,
    changedFields}`; validation applies them (masked MEMBER_VALIDATED diff); a rejection restores the record exactly
    (original `leftAt`, `leftReason`, data); the submission is audited (`SIGNUP_SUBMITTED`, `origin = PUBLIC`).
  - Security minors: `signup.rateLimit` read per club; at most 3 N-39 per account and 3 applicant N-01 per address per
    club and hour; consents under impersonation record `actorAccountId` and `origin = BACKOFFICE` (public: `PUBLIC`,
    member: `APP`); anonymous `identity-checks`, `family-group-lookups`, `upload-urls` and `uploads` answer `422
    SIGNUP_CLOSED`, decided on the committed configuration; bounded lookup inputs and indexed lookups
    (`contactEmails.email`, case/accent-insensitive `name_ci` on dogs); `SignupEdited`/`MemberUpdated` diffs masked like
    the audit; signup submissions retry a write conflict in their own transaction (`SignupTransactions`: one 201 and one
    `422 SIGNUP_ALREADY_PENDING`, never a 500); `maskedEmail` «m•••a@e•••.cat» of the account address N-39 goes to.
  - Step 5: marked cache evictions (`DomainEventHandler.evictsAfterCommit`) also run right after the publishing commit
    on this instance: `GET /signup` configuration, the template, the training grid, the public activities and the
    club configuration/host caches. `GET /signup` right after `PUT /parameters/signup.enabled` answers the new value.
- E3-T08: gate E3 audit fixes (api, 1/3), `roadmap/reviews/gate-E3/consolidated.md`.
  - `bin/e3-smoke`: the D1 class occupancy follows S14 R-14-03 for the seeded week (computed from `class_sessions`);
    the chart columns are the progression levels; `GET /signup` texts have no placeholder; the family fare is proposed.
  - M6: `GET /signup.texts` resolves `{deadlineDay}` (`inactivity.requestDeadlineDay`) and `{twoDogsMonthlyFee}` (the
    R-04-13 family fare, money in the locale); no family fare → `familyGroupIntro: null`.
  - M7/M9: `paymentMethods[MANUAL].instructions` = `CLUB.paymentProviders.MANUAL.instructions`. The applicant's N-01
    carries `upfront_total` (frozen `Member.signup.upfront.totalDue`) and `payment_instructions` (copy
    `notif.N-01.upfront.*`); the admins get their own copy (`notif.N-01.admin.*`: name, dogs, plan) with `OPEN_SIGNUP`,
    which the applicant's copy never carries. `SystemEmailRenderer`/`SystemNotificationService` take a copy variant.
    Fixed: `{dogs}` of N-01 was always empty (`Criteria.in` received the id list as one value).
  - M5: `GET /signup.upfront.planQuotes[]`, one quote per offered plan (and the member's own plan in add-dog mode),
    computed with the submission's code; the add-dog `TODAY` option exists after `billing.upfrontCutoffDay` too.
  - M8: assignable plans (`active ∧ module`, whatever `showOnSignup`) for the family fare, the D2 plan and `dryRun`,
    add-dog with the member's own plan, and D2 loading; `GET /members/{id}/signup.planOptions[]`. The public offer
    (`GET /signup.plans`, the applicant's `planIdRequested`) keeps `showOnSignup`.
  - M10 (E39): every `UpfrontPayment` carries `submissionId` (also in `Member.signup`/`Dog.signup`); D2, validation,
    `dryRun`, rejection and checkout read only the rows of each pending dog's submission. A plan change cancels only
    `DUE` rows and never rewrites an amount (`PAID`/`PARTIAL` kept and deducted); with a `CHECKOUT_PENDING` row it is
    refused (`409 INVALID_STATE`, `details.reason = CHECKOUT_PENDING`; `dryRun` warns `CHECKOUT_PENDING`).
  - M11: submission, add-dog, validation, rejection and the D2 edits of a pending row evict the D1 caches right after
    their commit; `GET /members/{id}/signup` returns `warnDays`, and nothing pending → `409 INVALID_STATE NOT_PENDING`.
  - M20 (E36): `GET /me/dogs` lists the member's own `PENDING` dogs (`id, name, breed, sex, ageYears, status`) and
    every dog carries `status`.
  - M21: `nextInvoiceDate` ≥ the first-month start (validation, `dryRun`); `birthDate` in the past and ≥ 1900-01-01
    (signup and member PATCH); `chip` normalised and checked per country profile (`ES` 15 digits, `GENERIC` 8–15
    alphanumerics) in `POST /signup`, `POST /me/dogs/signup` and the D2 PATCH of a pending dog.
  - M12: `GET /members/{id}/signup.dogs[].version`. Flags `allowFamilyGroupPending`/`requireDogDocumentAtSignup` in
    `GET /signup`. E35: the D1 «Gossos per nivell» columns are the active progression levels; the rest count in `others`.
- E3-T08 round 2 (review `roadmap/reviews/E3-T08-20260924-1756-codex.md`):
  - E39b: a plan change or a rejection closes a `PARTIAL` row (`CANCELLED`, amounts untouched) and writes a `PAID`
    correction row for the money received (`UpfrontPayment.correctionOf`); the new rows are «new quote − paid». Paid
    beyond the new quote → `SignupWarning.PAID_EXCEEDS_QUOTE` + `paidExceedsQuote` in the `dryRun` and the validation
    result. A rejection with a `PARTIAL` row answers `paidPaymentRequiresRefund: true`.
  - Fixed: a plan change on legacy rows (no `submissionId`) threw a `NullPointerException` (500).
  - A plan's billed price follows its billing mode: Teràpia (`MAINTENANCE`) offers, accepts and stores its
    `MAINTENANCE_FEE` price (`planOptions`, `proposals.priceId`, `member.priceId`); its upfront stays entry-only.
  - The checkout scope is the member's current submission, whatever its dogs' status, plus every pending dog: a
    validation with nothing paid (R-04-16) no longer empties it.
  - N-01 APP rows use the copies: the admins' row is `variant = admin`, the member's add-dog row the applicant copy
    with `upfront_total` and `payment_instructions` (allow-listed). `Notification.variant` records the copy on every row.
- E5-T14: follow-ups of the E5-T09 and E5-T11 reviews.
  - Demo `seed:demo --reanchor`: registrants go only into the weeks the run generated (`DemoSeedStep.Input.generatedWeeks`),
    never into a kept week. A kept, still-draft, non-past week whose `planning.weeks` row says `validate` is validated,
    so a weekly re-anchor leaves a bookable current week (`seeds/README.md`).
  - `DemoSeedActor.as` refuses to run outside the `local`/`test` profiles (FORBIDDEN, fail-closed without an environment).
  - ArchUnit: `COUNTER_WRITERS` allows only `BookingCounters` and also catches `::counters` method references;
    `CONSUMER_ENVELOPES` covers `*ExternalEvent`, and `ActivityExternalEvent` is no longer a `DomainEvent`.
  - `SystemNotificationService.appOnce/smsIntentOnce/pushIntentOnce` are `Propagation.NEVER` again; the S08 N-15
    consumer uses the new `…InTransaction` (`MANDATORY`) variants.
  - Behaviour change (R-08-13): `WaitlistEntry.offerNotifiedAt` is set after the N-15 rows and only when at least one
    was queued (a member with no account and no phone gets no mark and no N-46); a FIFO expiry clears it.
  - The plans route declares `@SecurityRequirement(name = "clubApiKey")` like the pages route (the snapshot is
    byte-identical); new tests: a SUSPENDED club with a wrong key or none answers `403 INVALID_API_KEY` on every keyed
    public route.
- E3-T07: gate E3 audit run on `de0e17f`, evidence only with no product change.
  - `roadmap/evidence/E3-T07/` holds the S04/S14 spec-test traceability (`trace.py`, CSV and summary: 40 of the 44 ids in scope pass; the 4 missing are front-layer ids).
  - `clean verify` passes (513 unit + 826 IT), both seed commands are idempotent, and the snapshot shows no drift.
  - `bin/e3-smoke` fails on its stale E3-era «Future verticals must stay empty» assertion since the E4-T05 planning seed.
- E5-T12: Josep's answers B29–B33 in the Playoff migration (S18 `MappingConfig` v5, E32), level PENDENT, E29 coverage.
  - The adapter moves to `migration/playoff-v2.yaml` (`version: 2`): «Familiar Abonat/curs» → `ABONAT_FAMILIAR`
    with the family behaviour (B31); `pendent` → level `PENDENT` + `LEVEL_PENDING` (B32); `cadells` → `CAD`; the photo
    reference goes to `Dog.sourceIds.playoffPhoto` for the cutover download (B29). A unit test fails when a mapped plan
    or level code is missing from `seeds/club-canic.yaml`, so `instructors`/`competició 1 gos` stay `PLAN_UNMAPPED`.
  - Optional `persones.csv` (R-18-04 (f)): the joined record adds only its dog to the principal's person, which keeps
    the NIF of the file (`PERSON_MERGED`); the anonymizer keeps the joins.
  - Accounts only for `ACTIVE` members (R-18-12); among shared emails the oldest «Data alta» owns the account (tie:
    lowest member number); the others get `EMAIL_SHARED` and a `familyGroups PROPOSED … field=holder@<row>` report line.
  - Seed level `PENDENT` «Pendent»/«Pendiente» (order 90, #9AA0A6, capacity 5, outside the progression).
  - Behaviour change: the D3 coverage (`GET /coverage`, R-06-06) lists only the active progression levels (E29), so
    Teràpia and Pendent no longer appear; `GET /levels` on the Cànic seed returns 10 levels.
- E6-T01: S10 contract (WP-10-A).
  - 22 S10 routes (`/instructor/day|week|week/export`, `/class-sessions/{id}/attendance` GET/PUT, `/attendances`,
    `/dogs/{id}/instructor-card`, `/me/history`, `/dogs/{id}/observations`, `/tasks*`, `GET /attachments`,
    `DELETE /attachments/{id}`, `/followup*`) with typed S10 §6 forms; they answer 501 behind the real guards
    (`InstructorController`, `TasksController`, `FollowupController`, `E6ContractConfiguration`).
  - Mongo documents and indexes: `attendances` (`clubs.bookings`), `tasks`, `followup_items`, `followup_read_marks`
    (`clubs.followup`), and the `{clubId, entityType, entityId, removedAt}` index of `attachments`.
  - `ClassSession.attendanceSummary` (S10-owned; a planning PATCH keeps it); `AttendanceStatusCalculator` feeds
    `attendanceStatus` to the S06 calendar and instructor day grid through `AttendanceStatusPort`.
  - `DogFollowupPort` (null default) for what the sheet and the card read from the follow-up side.
  - `DogOwnerAccess.ownerOf`: the follow-up guards read a dog's owner without importing census.
  - Upload purposes `TASK` and `DOG_OBSERVATIONS` (INSTRUCTOR/ADMIN, `TASKS`).
  - Events `AttendanceEvent` (`AttendanceMarked`, `NoShowNoticeDue`) and `FollowupEvent` (`TaskCreated`, `TaskUpdated`,
    `TaskDeleted`, `TaskCompleted`, `TaskReopened`, `AttachmentRemoved`), not published yet.
  - Tests: `E6ContractIT`, `E6PersistenceIT`, `E6ResponseContractTest`, `JobCatalogContractTest`, unit tests of the
    window and the guards, ArchUnit `clubs.bookings` ⊬ `clubs.followup` and `platform` ⊬ `clubs`.

### Changed

- E6-T01: `TASK_ALREADY_DONE` is 422 (was 409), as `CATALEG_ERRORS.md` §3 rule 0 says. The OpenAPI schema
  `AttachmentResponse` is now `Attachment`. `ATTACHMENT_LIMIT_REACHED` carries `details.max`.
- E6-T01 round 2 (organizer review):
  - `ClassSession.attendanceSummary.notifiedAfterEnd` (default 0 for summaries stored before it) counts the NOTIFIED
    rows whose booking stayed ACTIVE (R-10-05). The attendance total is `booked + notified − notifiedAfterEnd`, so each
    row counts once (R-10-02). A class with every row marked is now `DONE` in the calendar and the instructor day grid,
    not `PENDING`.
  - `POST /attachments` declares the optional `Idempotency-Key` (S10 §6). The same key replays the stored `201` body.
- E5-T12 round 2 (organizer review, R-18-14): two re-execution transitions of `migration:playoff` are rejected, not
  reconciled, with the report line `members ERROR REEXECUTION_UNSUPPORTED`: a record imported `ACTIVE` with an account
  or membership that now arrives `LEFT` (`field=status`), and a `persones.csv` join over a record already loaded as its
  own person (`field=persons`; every join of that principal is dropped). Unlike other errors they do not stop the
  apply: those records stay untouched, the rest is applied, the summary points to `--reset` on staging, and the
  command exits non-zero. Before, the apply kept a stale account link, or failed on `member_playoff_ids` /
  `ID_DOCUMENT_ALREADY_EXISTS` after a clean dry run.
- E5-T12 round 3 (organizer review, R-18-14): the re-execution protection of `migration:playoff` holds per
  destination member, not per source row.
  - Before planning, the planner resolves each record's member and protects every member of an unsupported record.
    It also protects a member that two persons of this load would share, such as a join removed or changed after a
    load, and a member that a record leaves for another one.
  - No row, own or alias, plans a member, dog or identity change for a protected member; each row gets its own
    `REEXECUTION_UNSUPPORTED` line. A joined record of a protected principal reports `field=persons`. A family group
    with a protected holder or member is left as it is (`familyGroups … field=familyGroup`). None of these blocks the
    rest of the load.
  - The summary line tells the dry run («would be left untouched; the rest would be applied») from the apply («were
    left untouched; the rest was applied»); a report with a blocking error says only «Validation failed».
  - Before, removing `persones.csv` after a load planned two changes for one member (the second overwrote the first).
    An ACTIVE same-NIF alias still changed a protected member, and a protected principal or family holder made the
    whole load fail.
- E5-T12 round 4 (organizer review; S18 R-18-14 amended 24-09; B34):
  - `REEXECUTION_UNSUPPORTED` now blocks the whole apply of `migration:playoff`, like any other error. The dry run
    still lists every such row. The report says «Validation failed; no changes applied» plus «N records cannot be
    reconciled with an earlier load (R-18-14). On staging, the way out is --reset and a new load.» The partial-apply
    wording and `MigrationReport.hasBlockingErrors()` are gone.
  - A join that disappears is detected against the stored relationships: a member that this input plans, with a stored
    alias (`externalIds.playoff[]`) that the input no longer resolves to it, is `field=persons`. That covers a joined
    record that is absent or skipped as an old leaver. A family group whose **stored** holder or member is protected is
    never planned (`field=familyGroup`).
  - Seed: new plan `COMPETICIO_1` «Competició 1 gos» (MONTHLY, 1 dog, entry STANDARD, `MONTHLY_FEE`, hidden on signup
    and web, order 50) with its `MONTHLY_FEE` price 4000 EUR (S05 §12), also in `club-canic-consumer.yaml`.
  - Mapping v2: «competició 1 gos» → `COMPETICIO_1`. The new required key `withoutPlan` lists typologies that are not
    migrated as a plan on purpose. «instructors» is the only one: no plan, no `PLAN_UNMAPPED`, and the INSTRUCTOR role
    stays.
- E5-T13 (rulings E33 and E34, follow-ups of the E5-T09 and E5-T10 reviews):
  - R-15-05 (E33): the first run of a process in a club with no `JobRun` of it, outside its window, records
    `SKIPPED{MISSED_WINDOW}` as a baseline without `JobFailed`/N-42, and the S17 matrix reads it as «never executed»
    (OK). The next misses alert as before.
  - R-15-17 (E34): a provider completion that reaches an `EXPIRED` PAY_TO_BOOK checkout, or an
    `UpfrontPaymentSucceeded` for a booking that is already cancelled, logs a WARN and marks the checkout session
    (`lateCompletionAt`, `providerPaymentId`) for the S12 refund (E8-T04 step 12). No event, no catalog change.
    `CheckoutService.complete` takes the provider payment id.
  - R-15-17 (E34, round 2): a completion of a `PENDING` PAY_TO_BOOK checkout past its `expiresAt` is a late
    completion, not an `INVALID_STATE`. The session is expired (line `CANCELLED`, `UpfrontPaymentFailed`, as P7 would
    do) and marked, with the WARN line, and the booking is never confirmed. This covers a booking the club cancelled
    (P7 never sees it) and a `PAYMENT_PENDING` booking whose deadline passed before P7 ran. A provider retry keeps the
    first mark.
  - P9: a run that claimed the platform cycle and ends `FAILED` (its own failure, a lost lease, or the reaper) gives
    the claim back through the new `Job.failed` hook. The claim records its `runId`.
  - AGENTS rule 4: the club-scoped views of a run leave out the platform-pass counters and items: `GET /jobs`
    `lastRun`, `GET /jobs/{name}/runs`, the run sheet, the club's `POST /jobs/{name}/trigger` response and the
    `JOB_TRIGGERED` audit. The stored `JobRun`, the platform trigger and `GET /platform/jobs/overview` keep them.
  - `TIME_FROM_CLOCK` also forbids `OffsetTime`, `Year`, `YearMonth` and `MonthDay` `now()`/`now(ZoneId)`.
  - OpenAPI: the five `/jobs*` operations and `/platform/clubs/{clubId}/jobs/{name}/trigger` no longer publish a bare
    `422` (`@ContractErrors(omit = 422)`); the snapshot loses 60 lines.
  - Tests renamed from `T_15_05_…` to `T_15_04_…` (T-15-04 is the R-15-05 test). `SchedulerTick` documents that it
    renews its lease only between clubs.

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
