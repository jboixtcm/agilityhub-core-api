### Round 2 report

Resumed the task returned by `check.py --next` (`in_progress`), preserving the ten implemented organizer corrections and their evidence. This continuation completes the required random-order verification, including two narrow test-isolation corrections exposed by that gate. Both final clean builds passed 3,689 unit and 1,490 integration tests (5,179 each), with zero failures, errors or skips and all coverage gates. The supported image-mode signup smoke passes on the exact verified JAR (`107`); fresh OpenAPI is byte-identical (`98`). The default Docker compile failed on dependency downloads (`97`/`103`), retained explicitly below. Final evidence follows below.

#### Files changed

- Payments application: `CheckoutService`, `PaymentRefunds`, `StripeWebhooks`, `PaymentRetryPolicy` (new), `CardPayments`, `PaymentPrivacy`, `PaymentProvider`, `PaymentProviderRegistry`, `FakePaymentProvider`, `stripe/StripePaymentProvider`.
- Payments persistence: `StripeInbox`, `PaymentOperationRepository`, `PaymentRetryState` (new).
- API: `BillingController`, `MemberBillingController`, `MyBillingController`; census `CensusResponses`, `CensusQuery`, `MemberIdentityQuery`; shared `MemberIdentityAccess`; identity `MeController`, `MeResponse`.
- Tests: `CardPaymentsIT`, `StripePaymentProviderTest`, `E8ContractIT`, `OpenApiSnapshotTest`, `NotificationCatalogContractTest`; resumed verification additionally changes `E4PersistenceIT` and shared `AbstractIntegrationTest` fixture isolation, plus `summarize-tests.py` to include the affected class and guard/profile checks.
- `docs/openapi/{openapi.json,CHANGELOG.md}`, `CHANGELOG.md`, this task, generated `roadmap/STATUS.md`, appended `roadmap/MESSAGES.md`, and Round 2 evidence/helper scripts.

#### Rules and tests implemented

| Review point | Result and regression evidence |
|---|---|
| 1 — R-12-20/21 | `T_12_17_lateCheckoutCannotOverwriteTheCheckoutThatPaidItsRows`: A expires, B pays, A completes/refunds/replays. B's entire payment document remains unchanged; A alone is refunded once. The capture/reference writes happen only on rows this checkout settles. Red in `60`. |
| 2 — R-12-21 | `T_12_17_upfrontRefundBeforeCompletionWaitsAndSettlesOnce`: unknown intent stays pending, later completion pays, recovery refunds once. Unrelated intents exhaust the bounded retry policy. Red in `60`. |
| 3 — R-12-20 | `T_12_17_refundMetadataTargetsSecondRowBeforeProviderResultAndKeepsAdminProvenance`: a real admin HTTP refund invokes the fake provider, which delivers the webhook before returning; the persisted result is still absent and only the second row is refunded. SDK assertions inspect `metadata.operationId`. Original race red in `60`; final callback version included in clean runs. |
| 4 — R-12-21 / S08 R-08-18 | SDK Checkout requests serialize `payment_method_types: [card]` in both modes. `T_12_16_unpaidCheckoutNeverSettlesSignupOrBooking` leaves signup/booking rows pending and stores `IGNORED` with its reason. Reds in `59`, `60`. |
| 5 — R-12-21, step 13 | `T_12_15_recoveryIsFairBackedOffAndDeadLettersAfterConfiguredAttempts`: 101 failures before a recoverable row, inserted in reverse time order; the next pass processes the recoverable row, retries respect deadlines, third failure is terminal, WARN contains the event/operation ID and a trace ID. `T_12_15_recoveryUsesTheClubsAttemptLimitAndRetainsItsDeadline` also proves a club limit of two. Original starvation red in `60`. |
| 6 — R-12-22 | `T_12_31_cardInvalidIsPublishedOnMemberAndMeOnlyForCards`: real member and `/me` responses before detach, after detach and after setup; absent for MANUAL/SEPA, foreign member denied. Red in `61`. Snapshot contract checks optional boolean fields. |
| 7 — S12 §3 / R-12-20 | Correlated operation supplies refund reason and actor; dashboard fallback uses Stripe's reason with no invented actor. Late refunds are recorded on their operation, never another checkout's rows. The callback regression fails with provenance removed (`67`, source restored in `finally`). |
| 8 — T-12-15 | `E8ContractIT.T_12_15_theStripeWebhookAuthenticatesTheBodyBeforeAnythingElse` plants an already-processed foreign-tenant event; its old whole-collection count fails (`61`). Counts now scope to the fixture tenants. Two randomized clean builds pass below; the resume also isolates the two shared fixtures those runs exposed. |
| 9 — R-12-13 | `T_12_15_chargingRunAcceptsAnotherCommandWithoutResubmittingInvoices`: CHARGING already worked; retained that behavior and fixed the contradictory OpenAPI description. The description assertion was red in `61`; obsolete card stub descriptions removed. |
| 10 | `T_12_17_pendingRefundKeepsOnlyAllowListedMetadata` verifies only operationId/reason survive in refund metadata, with unrelated data discarded; red in `61`. Signature-first Javadoc was already accurate and remains unchanged. Round evidence has no trailing whitespace. |

The synced N-35 trigger list now includes MemberCardInvalidated; removed the obsolete parity exception without editing a catalog.

#### Assumptions

- Resume checklist: the working tree was clean at entry. Reviewed the complete continuation diff; the two fixture changes preserve all assertions and use the current Spring environment, including production-profile denial. No production/API behavior, transaction, cache, time-zone or UI flow changed in this continuation. All new evidence remains under this task; previous attempts and Organizer verification are preserved. Published CI after the wrapper commits remains the organizer handoff.
- No new parameter, event, notification, error code or audit action. `billing.stripeMaxAttempts` bounds recovery (default 3); technical delays are 10, 20, ... seconds capped at 300, selected oldest-first in batches of 100. Exhausted rows retain outcome FAILED and processedAt and leave the pending set. Unpaid-completion `reason` is an internal inbox explanation, not an API error code.
- `/me` previously had no payment method. Added the safe top-level `{type, invalid?}` through the shared census port, gated by BILLING/current membership; the census member projection reuses its existing masked fields. No provider reference is exposed.
- A refund of a late capture belongs to its session/payment operation (`refund` checkpoint), not to the signup rows another checkout paid. Automatic provenance has no human actor; an admin refund retains its account ID.
- stripe-java 34 has no typed `payment_method_types` setter. Its supported extra-parameter builder writes the exact static list; SDK tests assert the serialized request, not a mock-only field. References: https://docs.stripe.com/api/checkout/sessions/create and https://docs.stripe.com/api/checkout/sessions/object ; refund metadata: https://docs.stripe.com/api/refunds/create .
- Security events for an unknown club deliberately have a null clubId; their before/after counter includes null plus the two test clubs. The no-business-write snapshot remains a stronger whole-database before/after comparison.
- No git write was run. Organizer documentation changes appeared concurrently in the index; they are untouched. No other task or Organizer verification section changed.

#### Questions / catalog proposals

- **Blocked: Stripe test account — @jordi** remains the accepted step-3 release limitation, not a roadmap blocker. Missing live-account assertions remain those listed in Round 1: authenticated per-club Checkout/setup, actual off-session success/decline/action and provider idempotency, refunds, real signed events, card retrieval and customer deletion. Both credential variables are absent (fresh presence-only evidence `100`); no live Stripe call was made.
- No new catalog proposal. N-35's earlier proposal is accepted and synced.
- Resume scope decision: the two previously routed fixture failures are addressed as test-harness prerequisites of review point 8's required randomized gate. `E4PersistenceIT` now owns its Saturday template ID; `AbstractIntegrationTest` rebinds the demo guard to the actual cached context's environment before each test (including `test,prod`). No production guard, test assertion or test selection was weakened, and no other roadmap task was started. The original failures remain in logs `84` and `90`; their recorded suite totals are `85` and `91`. The focused fixture run `92` and final clean runs `93`/`95` pass. Production-profile header checks and both DemoSeedActor guard tests also pass (summaries `94`/`96`).

#### Evidence

Earlier attempts (each retained in a distinct log; prefixes below are under `roadmap/evidence/E8-T04/`):

- `58-round2-sdk-red.log` (exit 1): wildcard map assertion did not compile → assert against the serialized SDK parameter map.
- `59-round2-sdk-red.log` (exit 1): card-only SDK parameter absent → configure the static card list.
- `60-round2-payments-red.log` (exit 1): late overwrite, lost early refund, refund target race, unpaid settlement and recovery starvation reproduced; `/me` fixture lacked an account → fix behavior and use a real account/membership fixture.
- `61-round2-contracts-red.log` (exit 1): missing invalid field, foreign-event global count, obsolete charge description and missing safe metadata reproduced → correct each contract.
- `62-round2-unit.log` (exit 1): SDK metadata is typed Object → compare the exact metadata map.
- `63-round2-unit.log` (exit 0): SDK and accepted notification-catalog parity checks passed.
- `64-round2-payments.log` (exit 1): security counter omitted null-club events for unknown clubs → include the documented null case.
- `65-round2-regressions.log` (exit 1): log sanitizer sometimes redacts the UUID in the message → assert the logger's MDC trace prefix.
- `66-round2-payments-green.log` (exit 0): all 26 payment tests passed; the refund race was then strengthened to use the real admin HTTP call and fake-provider return callback.
- `67-round2-provenance-red.log` (exit 1, deliberate mutation): the admin-refund callback rejects lost reason/actor; `prove-refund-provenance.py` restored the exact source in `finally`.
- `68-round2-openapi.log` (exit 1): `/me` contract asserted the old exact property list → update it and assert optional boolean card invalidation.
- `69-round2-openapi.log` (exit 0): fresh snapshot and all 10 OpenAPI contract tests passed.

- `70-round2-clean-random-one.log` (exit 0; summary `71`): first randomized clean build passed all 5,176 tests; retained as the pre-log-format-fix checkpoint.
- `72-round2-clean-random-two.log` (exit 1; summary `73`, exactly one failure in 5,176 tests): random order exposed a retry-log assertion tied to text format after another class selected JSON logging → assert the warning ID and trace in either supported format. Two fresh clean randomized runs follow after this test-only correction.

- `84-round2-final-clean-random-two.log` (exit 1; summary `85`): ListFieldsContractIT demo seeding inherited the previous prod-profile environment → the resume rebinds the actual cached context's guard; `92`, `93` and `95` pass.

- `90-round2-final-clean-random-two.log` (exit 1; summary `91`): E4PersistenceIT collided with E4ContractIT's `template-saturday` ID. The resume gives the persistence fixture its own ID; no Mongo uniqueness assertion changes.
- `74-round2-e3-smoke.log` has no captured exit and ends during image build. It is incomplete checkpoint evidence only; the resume runs a fresh smoke with its own log and exit.

- `97-resume-e3-smoke.log` (exit 1): the Docker Maven package step failed on truncated Maven Central downloads (full builder output `103-smoke-build-download-failure.log`, retrieval exit 0). The stack was removed. The final smoke uses the supported `--image` path with the JAR from clean verify `95`, the unchanged runtime Dockerfile stage, and an explicit byte comparison of the JAR copied out of the image. This avoids repeating the failing dependency downloads; it does not claim that the default Docker compile succeeded.
- `104-resume-verified-package-smoke.log` (exit 1): helper assumed build-info lived under BOOT-INF/classes → account for Maven's refreshed build timestamp.
- `105-resume-verified-package-smoke.log` (exit 1): helper read build-info from the wrong archive path → use Spring Boot's root META-INF entry and compare every stable build field.
- `106-resume-verified-package-smoke.log` (exit 1): all 2,884 compiled files matched, but Docker tried to update the read-only user buildx directory → use a private temporary BUILDX_CONFIG, as the smoke itself does.

Final verification evidence follows below.

#### Resumed final verification (historical)

The exact commands, exit codes and literal tails from Round 2 are preserved unchanged in `roadmap/evidence/E8-T04/round2-final-evidence.md`. The underlying logs `92`–`108` remain unchanged. Current verification is in Round 3 below.
