# E7-T02 — design notes (executor, 2026-09-27)

Working notes kept while implementing, so a later session (or the reviewer) can follow the decisions.

## Shape
- One `Notification` per (occurrence, code, audience, recipient[, dog]); `deliveries[]` = one per channel and destination.
- Engine documents leave the E1–E6 compatibility block (`accountId`, `channel`, `status`, …) `null`; only the SYSTEM path
  (`SystemNotificationService`, N-25/26/27/39/43/52/53) still writes it.
- `variables` (the formatted values the texts were rendered with; credential links never) is stored on the document:
  the log's detail and the E1–E6 tests' contract.

## Pipeline (S11 §6)
`notifications.<EventType>` handler (one per distinct catalog event type) → `NotificationEngine.handle` inside the outbox
transaction: spec → module guards → parameter conditions → owner facts (`NotificationFactsPort`, one adapter per owning
context) → template (club's, else created from the product seed `notif.N-xx.*`) → `stillRelevant` → recipients
(`RecipientResolver` over `MemberDirectoryPort` / `StaffDirectoryPort`) → locale → `VariableFormatter` → `TemplateRenderer`
→ `ChannelResolver` (R-11-03) → bulk upsert by `dedupKey` → owner hook `stored(...)` (same transaction) →
`NotificationQueued` → after commit, outside any transaction, `NotificationDispatcher.trigger(ids)`.

## Dedup
DEFAULT key `{occurrence}:{code}:{audience}:{recipientKey}[:{dogId}]`; `occurrence` = eventId unless the owner's facts give
another one: N-33 `N-33:{weekId}` (two entry points, one notice), N-42 `N-42:{job}:{localDay}` (one per process and day),
N-46 `N-46:{entryId}:{notifiedAt}` (a lost offer once, whichever of three events arrives first). N-13 and N-24 keep the
catalog rules.

## Dispatcher
Claim = `findAndModify` of a QUEUED delivery with `nextAttemptAt ≤ now` and no live lease (`claimedUntil`), setting a lease
and a token; the result is written back by token. Retries: failures 1–4 → +1, +5, +15, +60 min; the 5th → FAILED.
SMS cap: conditional `$inc` on `Club.usage` before the send (released on failure); at the cap → `SKIPPED_CAP`, forced EMAIL
deliveries, `SmsCapReached` once per club-local month.

## Deviations to report
- N-02: the welcome e-mail carries the S01 magic link (a credential) → it stays on identity's SYSTEM path; the engine
  writes the APP copy only (`excludedChannels = {EMAIL}` in the census facts).
- N-01/N-03 APP copies to an add-dog member: APPLICANT is EMAIL only (R-11-03) → no longer written.
- N-19 `class_date`: R-11-05 «ahir» (T-11-33) supersedes the E6 «never ahir».
- SMS preferences: «+SMS» is fixed (R-11-04); the legacy `{CATEGORY: {email, sms}}` preference shape is gone.
- `SKIPPED_NOT_ALLOWED` (organizer 2026-09-24): the non-prod Twilio allow-list guard.

## Corrections (second session, 28-09)
- The two deviations above about N-01/N-03 and N-19 were never implemented that way, and the task report supersedes them:
  an existing member who applies still gets N-01/N-03 in the app (S04 §8; `SignupGateFixesIT` asserts it), and N-19's
  `class_date` stays the class's own date in full (S10 §8, ruling E65), not «ahir» (report, Question 1).
- Retries: a delivery has at most 5 attempts (waits 1, 5, 15, 60 min; the 5th failure is final), as `RetryPolicy` says.
