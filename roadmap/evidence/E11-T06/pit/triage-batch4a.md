# E11-T06 batch 4 survivor triage: `PaymentRefunds`, `PaymentCheckouts`

Source: `batch4-not-killed.tsv`, the rows of `com.agilityhub.core.payments.application.PaymentRefunds` (43) and `PaymentCheckouts` (23).
Where a line has several conditionals, `batch4-mutations.xml` `<index>` identifies the mutated one. Its killed siblings on the same line are named in brackets.
Tests are staged in `.local/e11-t06-staged-tests-4/src/test/java/com/agilityhub/core/payments/application/` as `PaymentRefundsSurvivorsTest` (PRS) and `PaymentCheckoutsSurvivorsTest` (PCS).
Totals: 63 rows have a test and 3 rows have a reason.

| Class:line | Method | Mutator | Status | Decision |
|---|---|---|---|---|
| PaymentRefunds:121 | compensated | ConditionalsBoundary (`remaining <= 0`, idx 25) | SURVIVED | test: PaymentRefundsSurvivorsTest#T_12_17_aCreditPolicyAddsNoCreditForAPaymentAlreadyFullyRefunded |
| PaymentRefunds:122 | compensated | NegateConditionals (`bookingId != null`, idx 32) | SURVIVED | test: PaymentRefundsSurvivorsTest#T_12_17_aLateBookingCompletionOfABookingAlreadyCreditedQueuesNoRefund |
| PaymentRefunds:202 | entityType | NegateConditionals (`REFUND_INVOICE`) | SURVIVED | test: PaymentRefundsSurvivorsTest#T_12_17_aLateRefundOfAnExpiredCheckoutSettlesOnItsSession |
| PaymentRefunds:203 | entityType | NegateConditionals (`REFUND_LATE`, idx 17) | SURVIVED | test: PaymentRefundsSurvivorsTest#T_12_17_aLateRefundOfAnExpiredCheckoutSettlesOnItsSession |
| PaymentRefunds:203 | entityType | NegateConditionals (`findById(...).isEmpty()`, idx 24) | SURVIVED | test: PaymentRefundsSurvivorsTest#T_12_17_aLateRefundOfAnExpiredCheckoutSettlesOnItsSession (also #T_12_17_aLateRefundOfABookingPaymentSettlesOnItsUpfrontPayment) |
| PaymentRefunds:202 | entityType | EmptyObjectReturnVals (`"Invoice"` → `""`) | NO_COVERAGE | reason: unreachable defensive branch: `entityType` has only two callers, `reverse` (line 216) and `settled` (line 247), and both pass only `REFUND_LATE` operations, so the `"Invoice"` return never runs |
| PaymentRefunds:31 | invoice | VoidMethodCall (`provider.require`) | SURVIVED | test: PaymentRefundsSurvivorsTest#T_12_17_anInvoiceRefundNeedsTheRefundCapability |
| PaymentRefunds:91 | lambda$compensate$12 | NegateConditionals (`bookingId != null`, idx 20) | SURVIVED | test: PaymentRefundsSurvivorsTest#T_12_17_aCancellationAlreadyChargedAsCreditIsNotCompensatedAgain |
| PaymentRefunds:89 | lambda$compensate$12 | VoidMethodCall (`upfront.lock`) | SURVIVED | test: PaymentRefundsSurvivorsTest#T_12_17_aCreditPolicyAddsNoCreditForAPaymentAlreadyFullyRefunded |
| PaymentRefunds:159 | lambda$execute$19 | VoidMethodCall (`execution.fence`) | SURVIVED | test: PaymentRefundsSurvivorsTest#T_12_17_aRefundAnswerAfterALostClaimChangesNothing |
| PaymentRefunds:34 | lambda$invoice$0 | NullReturnVals | NO_COVERAGE | test: PaymentRefundsSurvivorsTest#T_12_17_anUnknownInvoiceIsNotFound |
| PaymentRefunds:39 | lambda$invoice$4 | NegateConditionals (`refunds() == null`) | SURVIVED | test: PaymentRefundsSurvivorsTest#T_12_17_anInvoiceRefundLocksItsKeyAndReservesAllOfACollectionWithoutRefunds (also #T_12_17_anInvoiceRefundCountsTheRefundsAlreadySettledOnItsCollection) |
| PaymentRefunds:33 | lambda$invoice$4 | VoidMethodCall (`IdempotentOperation.lock`) | SURVIVED | test: PaymentRefundsSurvivorsTest#T_12_17_anInvoiceRefundLocksItsKeyAndReservesAllOfACollectionWithoutRefunds |
| PaymentRefunds:136 | lambda$lateBooking$18 | VoidMethodCall (`upfront.lock`) | SURVIVED | test: PaymentRefundsSurvivorsTest#T_12_17_aLateBookingCompletionLocksThePaymentBeforeReadingIt |
| PaymentRefunds:207 | lambda$operation$22 | NullReturnVals | SURVIVED | test: PaymentRefundsSurvivorsTest#T_12_17_aSettlementNamingAnUnknownCommandIsRejected |
| PaymentRefunds:65 | lambda$reserve$9 | BooleanFalseReturnVals | SURVIVED | test: PaymentRefundsSurvivorsTest#T_12_17_aRepeatedInvoiceRefundAnswersItsReservation |
| PaymentRefunds:225 | lambda$reverse$23 | BooleanTrueReturnVals | SURVIVED | test: PaymentRefundsSurvivorsTest#T_12_17_aFailedInvoiceRefundRestoresOnlyThatRefundAndRecountsTheOthers |
| PaymentRefunds:228 | lambda$reverse$25 | PrimitiveReturns | NO_COVERAGE | test: PaymentRefundsSurvivorsTest#T_12_17_aFailedInvoiceRefundRestoresOnlyThatRefundAndRecountsTheOthers |
| PaymentRefunds:236 | lambda$reverse$26 | BooleanTrueReturnVals | SURVIVED | test: PaymentRefundsSurvivorsTest#T_12_17_aFailedUpfrontRefundRestoresOnlyThatRefundWarnsAndRecomputesUnderThePaymentLock |
| PaymentRefunds:238 | lambda$reverse$27 | InvertNegs | SURVIVED | test: PaymentRefundsSurvivorsTest#T_12_17_aFailedUpfrontRefundRestoresOnlyThatRefundWarnsAndRecomputesUnderThePaymentLock |
| PaymentRefunds:260 | lambda$settled$28 | BooleanFalseReturnVals | SURVIVED | test: PaymentRefundsSurvivorsTest#T_12_17_aRefundTheCollectionAlreadyHoldsIsNotSettledTwice |
| PaymentRefunds:279 | lambda$settled$32 | BooleanFalseReturnVals | SURVIVED | test: PaymentRefundsSurvivorsTest#T_12_17_aRefundThePaymentAlreadyHoldsIsNotSettledTwice |
| PaymentRefunds:51 | lambda$upfront$5 | NullReturnVals | NO_COVERAGE | test: PaymentRefundsSurvivorsTest#T_12_17_anUnknownUpfrontPaymentIsNotFound |
| PaymentRefunds:52 | lambda$upfront$6 | BooleanFalseReturnVals | NO_COVERAGE | test: PaymentRefundsSurvivorsTest#T_12_17_aRepeatedUpfrontRefundAnswersItsOwnCommand |
| PaymentRefunds:52 | lambda$upfront$6 | BooleanTrueReturnVals | NO_COVERAGE | reason: unreachable with real data — the filter `op.targetId().equals(id)` only differs from `true` when `byKey(key)` returns a command of another target, but both callers build the key from the target id (`"upfront-refund:" + id + ":" + idempotencyKey`, UpfrontPaymentsController.java:79, `id` an existing payment — line 51 throws NOT_FOUND first — and the key a `UUID`; `"refund:" + id` or `"refund:" + id + ":" + n`, PaymentRefunds.java:105-111), and every command writer (PaymentRefunds.java:73,83,142; CardPayments.java:99; PaymentPrivacy.java:21) stores a key that embeds its own target: `upfront-refund:`/`invoice-refund:` + target (UpfrontPaymentsController.java:79, InvoicesController.java:165), `refund:` + paymentId with target paymentId (PaymentRefunds.java:142-143), `late:` + provider reference (PaymentRefunds.java:81), `forget:` + customer (PaymentPrivacy.java:20), invoice id / `<invoiceId>:<attempt>` for CHARGE (CardPayments.java:71,89). Payment ids are UUIDs without `:` (UpfrontPayments.java:70,78,180; ManualUpfrontPayments.java:37), so no other target's key can spell this one, and `(clubId, key)` is unique (PaymentOperationRepository.java:19-21) |
| PaymentRefunds:50 | lambda$upfront$8 | VoidMethodCall (`IdempotentOperation.lock`) | SURVIVED | test: PaymentRefundsSurvivorsTest#T_12_17_anUpfrontRefundLocksItsKeyThenThePaymentBeforeReadingItsCapture |
| PaymentRefunds:56 | lambda$upfront$8 | VoidMethodCall (`upfront.lock`) | SURVIVED | test: PaymentRefundsSurvivorsTest#T_12_17_anUpfrontRefundLocksItsKeyThenThePaymentBeforeReadingItsCapture |
| PaymentRefunds:53 | lambda$upfront$8 | NullReturnVals | NO_COVERAGE | test: PaymentRefundsSurvivorsTest#T_12_17_aRepeatedUpfrontRefundAnswersItsOwnCommand |
| PaymentRefunds:80 | late | ConditionalsBoundary (`amount <= 0`) | SURVIVED | test: PaymentRefundsSurvivorsTest#T_12_17_aLateCompletionOfNothingQueuesNoRefund |
| PaymentRefunds:134 | lateBooking | ConditionalsBoundary (`amount <= 0`) | SURVIVED | test: PaymentRefundsSurvivorsTest#T_12_17_aLateBookingCompletionOfNothingQueuesNoRefund |
| PaymentRefunds:194 | reconcile | VoidMethodCall (`retries.warnRefund`) | SURVIVED | test: PaymentRefundsSurvivorsTest#T_12_17_aFailedUpfrontRefundRestoresOnlyThatRefundWarnsAndRecomputesUnderThePaymentLock |
| PaymentRefunds:115 | reconsiderCompensation | VoidMethodCall (`upfront.lock`) | SURVIVED | test: PaymentRefundsSurvivorsTest#T_12_17_aFailedUpfrontRefundRestoresOnlyThatRefundWarnsAndRecomputesUnderThePaymentLock |
| PaymentRefunds:66 | reserve | NullReturnVals | NO_COVERAGE | test: PaymentRefundsSurvivorsTest#T_12_17_aRepeatedInvoiceRefundAnswersItsReservation |
| PaymentRefunds:218 | reverse | InvertNegs | SURVIVED | test: PaymentRefundsSurvivorsTest#T_12_17_aFailedLateRefundReversesItsCheckpointWithANegativeAudit |
| PaymentRefunds:273 | settled | ConditionalsBoundary (`amount <= 0`, idx 284) | SURVIVED | reason: unreachable with real data — `<= 0` and `< 0` differ only for a zero refund, and `settled` has one caller, `reconcile` (PaymentRefunds.java:191), whose only callers pass the `amount` of a signed Stripe refund object (StripeWebhooks.java:114-116, 120-122); Stripe refunds are positive integers in minor units, and every refund this API requests is reserved above zero (PaymentRefunds.java:72, 80, 134) |
| PaymentRefunds:249 | settled | NegateConditionals (`if (changed)`) | SURVIVED | test: PaymentRefundsSurvivorsTest#T_12_17_aLateRefundOfAnExpiredCheckoutSettlesOnItsSession (also #T_12_17_aRedeliveredLateRefundSettlementChangesNothing) |
| PaymentCheckouts:27 | create | NegateConditionals (`setup ? CARD_SETUP : CHECKOUT`) | SURVIVED | test: PaymentCheckoutsSurvivorsTest#T_12_16_aPaymentCheckoutNeedsTheCheckoutCapability (also #T_12_31_aCardSetupNeedsTheCardSetupCapability) |
| PaymentCheckouts:30 | create | NegateConditionals (`bookings.cancelled`) | SURVIVED | test: PaymentCheckoutsSurvivorsTest#T_12_16_aCancelledBookingOpensNoCheckout (also #T_12_16_aBookingWithAnOpenCheckoutAnswersItUnderTheKey) |
| PaymentCheckouts:34 | create | NegateConditionals (`memberId`, idx 76) | NO_COVERAGE | test: PaymentCheckoutsSurvivorsTest#T_12_16_aPaymentOfAnotherMemberIsNotFoundForTheBooking |
| PaymentCheckouts:34 | create | NegateConditionals (`bookingId`, idx 81) | NO_COVERAGE | test: PaymentCheckoutsSurvivorsTest#T_12_16_aPaymentOfAnotherBookingIsNotFound |
| PaymentCheckouts:27 | create | VoidMethodCall (`provider.require`) | SURVIVED | test: PaymentCheckoutsSurvivorsTest#T_12_16_aPaymentCheckoutNeedsTheCheckoutCapability |
| PaymentCheckouts:28 | create | VoidMethodCall (`signup.redirect(success)`, idx 20) | SURVIVED | test: PaymentCheckoutsSurvivorsTest#T_12_16_anUnsafeSuccessUrlIsRefusedBeforeAnyWrite |
| PaymentCheckouts:28 | create | VoidMethodCall (`signup.redirect(cancel)`, idx 24) | SURVIVED | test: PaymentCheckoutsSurvivorsTest#T_12_16_anUnsafeCancelUrlIsRefusedBeforeAnyWrite |
| PaymentCheckouts:40 | create | NullReturnVals | NO_COVERAGE | test: PaymentCheckoutsSurvivorsTest#T_12_16_aBookingWithAnOpenCheckoutAnswersItUnderTheKey |
| PaymentCheckouts:33 | lambda$create$0 | NullReturnVals | NO_COVERAGE | test: PaymentCheckoutsSurvivorsTest#T_12_16_anUnknownPaymentOfTheBookingIsNotFound |
| PaymentCheckouts:40 | lambda$create$1 | NullReturnVals | NO_COVERAGE | test: PaymentCheckoutsSurvivorsTest#T_12_16_aBookingWithAnOpenCheckoutAnswersItUnderTheKey |
| PaymentCheckouts:48 | lambda$create$2 | BooleanFalseReturnVals | SURVIVED | test: PaymentCheckoutsSurvivorsTest#T_12_16_withoutPaymentIdsTheCheckoutChargesTheMembersRowsOutsideAnyBookingUnderBothLocks |
| PaymentCheckouts:48 | lambda$create$2 | BooleanTrueReturnVals | SURVIVED | test: PaymentCheckoutsSurvivorsTest#T_12_16_withoutPaymentIdsTheCheckoutChargesTheMembersRowsOutsideAnyBookingUnderBothLocks |
| PaymentCheckouts:49 | lambda$create$3 | NullReturnVals | NO_COVERAGE | test: PaymentCheckoutsSurvivorsTest#T_12_16_anUnknownExplicitPaymentIsNotFound |
| PaymentCheckouts:47 | lambda$create$6 | NegateConditionals (`paymentIds.isEmpty()`, idx 41; [setup] idx 32 and [`== null`] idx 38 killed) | SURVIVED | test: PaymentCheckoutsSurvivorsTest#T_12_16_anEmptyPaymentIdListChargesTheMembersRowsLikeNone (also #T_12_16_explicitPaymentIdsOfABookingAreChargedWithTheBookingAsReference) |
| PaymentCheckouts:62 | lambda$create$6 | NegateConditionals (`bookingId == null`, idx 210) | SURVIVED | test: PaymentCheckoutsSurvivorsTest#T_12_16_withoutPaymentIdsTheCheckoutChargesTheMembersRowsOutsideAnyBookingUnderBothLocks (also #T_12_16_explicitPaymentIdsOfABookingAreChargedWithTheBookingAsReference) |
| PaymentCheckouts:62 | lambda$create$6 | NegateConditionals (`setup ? null : "off_session"`, idx 220) | SURVIVED | test: PaymentCheckoutsSurvivorsTest#T_12_16_withoutPaymentIdsTheCheckoutChargesTheMembersRowsOutsideAnyBookingUnderBothLocks (also #T_12_31_aStandaloneCardSetupChargesNoRowAndIsMarked) |
| PaymentCheckouts:65 | lambda$create$6 | NegateConditionals (`if (setup)`) | SURVIVED | test: PaymentCheckoutsSurvivorsTest#T_12_31_aStandaloneCardSetupChargesNoRowAndIsMarked (also #T_12_16_withoutPaymentIds…) |
| PaymentCheckouts:44 | lambda$create$6 | VoidMethodCall (`IdempotentOperation.lock`) | SURVIVED | test: PaymentCheckoutsSurvivorsTest#T_12_16_withoutPaymentIdsTheCheckoutChargesTheMembersRowsOutsideAnyBookingUnderBothLocks |
| PaymentCheckouts:44 | lambda$create$6 | VoidMethodCall (`members.lock`) | SURVIVED | test: PaymentCheckoutsSurvivorsTest#T_12_16_withoutPaymentIdsTheCheckoutChargesTheMembersRowsOutsideAnyBookingUnderBothLocks |
| PaymentCheckouts:65 | lambda$create$6 | VoidMethodCall (`markStandaloneCardSetup`) | SURVIVED | test: PaymentCheckoutsSurvivorsTest#T_12_31_aStandaloneCardSetupChargesNoRowAndIsMarked |
| PaymentCheckouts:46 | lambda$create$6 | NullReturnVals | NO_COVERAGE | test: PaymentCheckoutsSurvivorsTest#T_12_16_aRetriedRequestSendsTheFrozenProviderRequestAgain |
| PaymentCheckouts:73 | lambda$status$8 | NullReturnVals | NO_COVERAGE | test: PaymentCheckoutsSurvivorsTest#T_12_16_theStatusOfAnUnknownSessionIsNotFound |
| PaymentRefunds:250 | settled | BooleanFalseReturnVals | SURVIVED | test: PaymentRefundsSurvivorsTest#T_12_17_aLateRefundOfAnExpiredCheckoutSettlesOnItsSession |
| PaymentRefunds:265 | settled | BooleanFalseReturnVals | SURVIVED | test: PaymentRefundsSurvivorsTest#T_12_17_anInvoiceRefundSettlesOnItsCollection |
| PaymentRefunds:292 | settled | BooleanFalseReturnVals | SURVIVED | test: PaymentRefundsSurvivorsTest#T_12_17_anUpfrontRefundSettlesOnItsPayment |
| PaymentRefunds:250 | settled | BooleanTrueReturnVals | SURVIVED | test: PaymentRefundsSurvivorsTest#T_12_17_aRedeliveredLateRefundSettlementChangesNothing |
| PaymentRefunds:260 | settled | BooleanTrueReturnVals | NO_COVERAGE | test: PaymentRefundsSurvivorsTest#T_12_17_aRefundTheCollectionAlreadyHoldsIsNotSettledTwice |
| PaymentRefunds:279 | settled | BooleanTrueReturnVals | NO_COVERAGE | test: PaymentRefundsSurvivorsTest#T_12_17_aRefundThePaymentAlreadyHoldsIsNotSettledTwice |
| PaymentRefunds:48 | upfront | VoidMethodCall (`provider.require`) | SURVIVED | test: PaymentRefundsSurvivorsTest#T_12_17_anUpfrontRefundNeedsTheRefundCapability |
| PaymentCheckouts:74 | status | NegateConditionals (`"EXPIRED".equals`, idx 26; [`"COMPLETE"`] idx 17 killed) | SURVIVED | test: PaymentCheckoutsSurvivorsTest#T_12_16_theReturnScreenSeesEachSessionStatus |

Observation (not a confirmed bug, no change made): `PaymentRefunds.settled` line 273 answers `CURRENCY_MISMATCH` for a non-positive refund amount on the upfront path. The currencies match in that case, so the code describes the wrong cause. The test pins the current behaviour.
