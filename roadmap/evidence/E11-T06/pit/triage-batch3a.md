# E11-T06 PIT batch 3 triage (a): `CheckoutService` and `CardPayments`

Source: `batch3-not-killed.tsv` (rows for these two classes only: 66). Staged tests (to be moved into `src/test/java`):
`.local/e11-t06-staged-tests-3/src/test/java/com/agilityhub/core/payments/application/CheckoutServiceSurvivorsTest.java` and
`.../CardPaymentsSurvivorsTest.java`. Which conditional a "negated conditional" row targets comes from `<index>` in `batch3-mutations.xml`.
The staged tests have not been compiled or run yet: the batch-3 PIT run was still using the working tree.

Totals: 64 rows get a test and 2 get a reason. No production bug found.

| Class:line | Method | Mutator | Status | Decision |
|---|---|---|---|---|
| CheckoutService:37 | `<init>` | VoidMethodCall (setPropagationBehavior) | SURVIVED | test: CheckoutServiceSurvivorsTest#T_04_19_theProviderExpiryAfterARejectionRunsAfterTheCommitOutsideAnyTransaction |
| CheckoutService:281 | completeSetup | NegateConditionals | SURVIVED | test: CheckoutServiceSurvivorsTest#T_12_31_aStandaloneCardSetupSavesTheCardOnTheCensusUnderItsLock |
| CheckoutService:280 | completeSetup | VoidMethodCall (members.lock) | SURVIVED | test: CheckoutServiceSurvivorsTest#T_12_31_aStandaloneCardSetupSavesTheCardOnTheCensusUnderItsLock |
| CheckoutService:283 | completeSetup | BooleanFalseReturnVals | SURVIVED | test: CheckoutServiceSurvivorsTest#T_12_31_aStandaloneCardSetupSavesTheCardOnTheCensusUnderItsLock |
| CheckoutService:279 | completeSetup | BooleanTrueReturnVals | NO_COVERAGE | test: CheckoutServiceSurvivorsTest#T_12_31_aClosedSetupSessionTakesNoCard |
| CheckoutService:283 | completeSetup | BooleanTrueReturnVals | SURVIVED | test: CheckoutServiceSurvivorsTest#T_12_31_aSetupTheSessionNoLongerWaitsForIsNotCompleted |
| CheckoutService:249 | completeWebhook | NegateConditionals (idx 44, `"setup".equals(mode)`) | SURVIVED | test: CheckoutServiceSurvivorsTest#T_12_16_aPaymentWhoseAmountDiffersFromTheSessionsIsRefused |
| CheckoutService:263 | completeWebhook | NegateConditionals (idx 163, `bookingId() != null`) | SURVIVED | test: CheckoutServiceSurvivorsTest#T_15_25_aPendingBookingCheckoutPaidAfterItsBookingWasCancelledExpiresAndIsRefunded |
| CheckoutService:264 | completeWebhook | NegateConditionals (`status == PENDING`) | NO_COVERAGE | test: CheckoutServiceSurvivorsTest#T_15_25_anExpiredBookingCheckoutPaidAfterItsBookingWasCancelledIsOnlyRefunded |
| CheckoutService:246 | completeWebhook | VoidMethodCall (members.lock) | SURVIVED | test: CheckoutServiceSurvivorsTest#T_12_16_aPaymentWhoseAmountDiffersFromTheSessionsIsRefused |
| CheckoutService:264 | completeWebhook | VoidMethodCall (late) | NO_COVERAGE | test: CheckoutServiceSurvivorsTest#T_15_25_aPendingBookingCheckoutPaidAfterItsBookingWasCancelledExpiresAndIsRefunded |
| CheckoutService:265 | completeWebhook | VoidMethodCall (lateCompletion) | NO_COVERAGE | test: CheckoutServiceSurvivorsTest#T_15_25_anExpiredBookingCheckoutPaidAfterItsBookingWasCancelledIsOnlyRefunded |
| CheckoutService:302 | completed | NegateConditionals (idx 68, cause text) | SURVIVED | test: CheckoutServiceSurvivorsTest#T_04_22_aLateCompletionWarnsWithItsCause (the reconciliation WARN names the signup's or the booking's cause; added after the audit, which rejected the first "logging only" reason) |
| CheckoutService:61 | create | VoidMethodCall (redirect(cancel), idx 44) | SURVIVED | test: CheckoutServiceSurvivorsTest#T_04_22_anUnsafeCancelUrlIsRefusedBeforeAnySessionOpens |
| CheckoutService:67 | create | VoidMethodCall (IdempotentOperation.release) | SURVIVED | test: CheckoutServiceSurvivorsTest#T_04_22_aProviderFailureReleasesTheKeyAndExpiresTheSessionUnderTheCensusLock |
| CheckoutService:74 | create | VoidMethodCall (addSuppressed) | SURVIVED | test: CheckoutServiceSurvivorsTest#T_04_22_aFailedCleanUpKeepsTheProviderFailureAsSuppressed |
| CheckoutService:155 | expireLapsed | BooleanTrueReturnVals | SURVIVED | test: CheckoutServiceSurvivorsTest#T_15_23_aSessionNoLongerInScopeIsNotExpiredByStepH |
| CheckoutService:104 | lambda$abandon$2 | VoidMethodCall (members.lock) | SURVIVED | test: CheckoutServiceSurvivorsTest#T_04_22_aProviderFailureReleasesTheKeyAndExpiresTheSessionUnderTheCensusLock |
| CheckoutService:237 | lambda$complete$10 | VoidMethodCall (members.lock) | SURVIVED | test: CheckoutServiceSurvivorsTest#T_04_22_aLateSignupCompletionRefundsWhatItsRowsWereDue |
| CheckoutService:237 | lambda$complete$9 | NullReturnVals | NO_COVERAGE | test: CheckoutServiceSurvivorsTest#T_04_22_completingAnUnknownSessionIsNotFound |
| CheckoutService:244 | lambda$completeWebhook$11 | NullReturnVals | NO_COVERAGE | test: CheckoutServiceSurvivorsTest#T_12_16_anUnknownSessionIsNotFound |
| CheckoutService:247 | lambda$completeWebhook$12 | NullReturnVals | NO_COVERAGE | test: CheckoutServiceSurvivorsTest#T_12_16_withoutAStoredAmountTheSessionCapturesWhatItsRowsStillOwe |
| CheckoutService:248 | lambda$completeWebhook$13 | MathMutator (− → +) | NO_COVERAGE | test: CheckoutServiceSurvivorsTest#T_12_16_withoutAStoredAmountTheSessionCapturesWhatItsRowsStillOwe |
| CheckoutService:248 | lambda$completeWebhook$13 | PrimitiveReturns (0) | NO_COVERAGE | test: CheckoutServiceSurvivorsTest#T_12_16_withoutAStoredAmountTheSessionCapturesWhatItsRowsStillOwe |
| CheckoutService:247 | lambda$completeWebhook$14 | EmptyObjectReturnVals (0L) | NO_COVERAGE | test: CheckoutServiceSurvivorsTest#T_12_16_withoutAStoredAmountTheSessionCapturesWhatItsRowsStillOwe |
| CheckoutService:63 | lambda$create$0 | VoidMethodCall (IdempotentOperation.lock) | SURVIVED | test: CheckoutServiceSurvivorsTest#T_04_22_aReplayedRequestLocksItsKeyAndTheCensusInBothWritesAndStoresTheAnswer |
| CheckoutService:82 | lambda$create$1 | VoidMethodCall (IdempotentOperation.lock) | SURVIVED | test: CheckoutServiceSurvivorsTest#T_04_22_aReplayedRequestLocksItsKeyAndTheCensusInBothWritesAndStoresTheAnswer |
| CheckoutService:82 | lambda$create$1 | VoidMethodCall (members.lock) | SURVIVED | test: CheckoutServiceSurvivorsTest#T_04_22_aReplayedRequestLocksItsKeyAndTheCensusInBothWritesAndStoresTheAnswer |
| CheckoutService:327 | lambda$expire$19 | NullReturnVals | NO_COVERAGE | test: CheckoutServiceSurvivorsTest#T_04_22_expiringAnUnknownSessionIsNotFound |
| CheckoutService:156 | lambda$expireLapsed$6 | VoidMethodCall (members.lock) | SURVIVED | test: CheckoutServiceSurvivorsTest#T_15_23_aSessionNoLongerInScopeIsNotExpiredByStepH |
| CheckoutService:157 | lambda$expireLapsed$6 | BooleanTrueReturnVals | NO_COVERAGE | test: CheckoutServiceSurvivorsTest#T_15_23_aSessionNoLongerInScopeIsNotExpiredByStepH |
| CheckoutService:158 | lambda$expireLapsed$6 | BooleanTrueReturnVals | NO_COVERAGE | test: CheckoutServiceSurvivorsTest#T_15_23_aLapsedSessionClosedMeanwhileGivesNoRowsBack |
| CheckoutService:352 | lambda$lateCompletion$23 | PrimitiveReturns (0) | NO_COVERAGE | test: CheckoutServiceSurvivorsTest#T_04_22_aLateSignupCompletionRefundsWhatItsRowsWereDue |
| CheckoutService:352 | lambda$lateCompletion$24 | EmptyObjectReturnVals (0L) | NO_COVERAGE | test: CheckoutServiceSurvivorsTest#T_04_22_aLateSignupCompletionRefundsWhatItsRowsWereDue |
| CheckoutService:120 | lambda$prepare$4 | BooleanFalseReturnVals | SURVIVED | test: CheckoutServiceSurvivorsTest#T_04_22_aRowAlreadyWaitingForACheckoutOpensNoOther |
| CheckoutService:107 | prepare | VoidMethodCall (members.lock) | SURVIVED | test: CheckoutServiceSurvivorsTest#T_04_22_aReplayedRequestLocksItsKeyAndTheCensusInBothWritesAndStoresTheAnswer |
| CheckoutService:218 | prepareBooking | VoidMethodCall (sessions.chargeAmount) | SURVIVED | test: CheckoutServiceSurvivorsTest#T_08_24_aBookingCheckoutKeepsTheAmountItCharges |
| CheckoutService:183 | rejected | NegateConditionals (idx 21, `memberLeft`) | SURVIVED | test: CheckoutServiceSurvivorsTest#T_04_19_aRejectionLeavesAnotherSubmissionsCheckoutOpenWhileTheMemberStays |
| CardPayments:53 | chargeRun | ConditionalsBoundary (`<` → `<=`) | SURVIVED | reason: equivalent; the extra iteration runs only when `start == size` (0, 25, 50…), where `subList(size, min(size+25, size))` is empty, so `execute` is never called |
| CardPayments:31 | chargeRun | VoidMethodCall (provider.require) | SURVIVED | test: CardPaymentsSurvivorsTest#T_12_15_aRunIsNotChargedWithoutAnOffSessionProvider |
| CardPayments:35 | lambda$chargeRun$0 | NullReturnVals | NO_COVERAGE | test: CardPaymentsSurvivorsTest#T_12_15_anUnknownRunIsNotFound |
| CardPayments:38 | lambda$chargeRun$1 | NullReturnVals | NO_COVERAGE | test: CardPaymentsSurvivorsTest#T_12_15_aReplayedRequestAnswersItsStoredOperationsAndSkips |
| CardPayments:47 | lambda$chargeRun$2 | NullReturnVals | SURVIVED | test: CardPaymentsSurvivorsTest#T_12_15_anInvoiceWithoutAUsableCardIsSkippedUnderTheKeysLock |
| CardPayments:34 | lambda$chargeRun$3 | VoidMethodCall (IdempotentOperation.lock) | SURVIVED | test: CardPaymentsSurvivorsTest#T_12_15_anInvoiceWithoutAUsableCardIsSkippedUnderTheKeysLock |
| CardPayments:112 | lambda$execute$10 | VoidMethodCall (returned.set) | SURVIVED | test: CardPaymentsSurvivorsTest#T_12_15_anExhaustedChargeKeepsTheIntentTheProviderAnsweredAndRecountsTheRun |
| CardPayments:129 | lambda$execute$11 | NegateConditionals (idx 41, `providerRef() == null`) | SURVIVED | test: CardPaymentsSurvivorsTest#T_12_15_anExhaustedChargeKeepsTheIntentTheProviderAnsweredAndRecountsTheRun |
| CardPayments:129 | lambda$execute$11 | VoidMethodCall (collections.submitted) | NO_COVERAGE | test: CardPaymentsSurvivorsTest#T_12_15_anExhaustedChargeKeepsTheIntentTheProviderAnsweredAndRecountsTheRun |
| CardPayments:131 | lambda$execute$11 | VoidMethodCall (progress) | SURVIVED | test: CardPaymentsSurvivorsTest#T_12_15_anExhaustedChargeKeepsTheIntentTheProviderAnsweredAndRecountsTheRun |
| CardPayments:114 | lambda$execute$8 | VoidMethodCall (execution.fence) | SURVIVED | test: CardPaymentsSurvivorsTest#T_12_15_aLostClaimWritesNoIntent |
| CardPayments:116 | lambda$execute$9 | VoidMethodCall (execution.fence) | SURVIVED | test: CardPaymentsSurvivorsTest#T_12_15_aClaimLostAfterTheIntentWriteNeverCompletesTheCommand |
| CardPayments:188 | lambda$invoice$24 | NullReturnVals | NO_COVERAGE | test: CardPaymentsSurvivorsTest#T_12_15_retryingAnUnknownInvoiceIsNotFound |
| CardPayments:63 | lambda$prepare$4 | NegateConditionals (idx 7, `kind == CHARGE`) | SURVIVED | test: CardPaymentsSurvivorsTest#T_12_15_aCollectingInvoiceReusesItsOpenChargeCommandOnly |
| CardPayments:65 | lambda$prepare$5 | BooleanTrueReturnVals | SURVIVED | test: CardPaymentsSurvivorsTest#T_12_15_aPendingInvoiceIsChargedOnItsCreatedAttemptWhichBecomesSubmitted |
| CardPayments:176 | lambda$progress$17 | BooleanTrueReturnVals | SURVIVED | test: CardPaymentsSurvivorsTest#T_12_15_theRunCountsOnlyItsCardInvoicesAsChargedOrFailed |
| CardPayments:151 | lambda$resolve$15 | BooleanTrueReturnVals | SURVIVED | test: CardPaymentsSurvivorsTest#T_12_16_anExpiredCardFailureInvalidatesTheChargedCardAndReconcilesOnlyItsCommand |
| CardPayments:153 | lambda$resolve$16 | VoidMethodCall (events.publish) | SURVIVED | test: CardPaymentsSurvivorsTest#T_12_16_anExpiredCardFailureInvalidatesTheChargedCardAndReconcilesOnlyItsCommand |
| CardPayments:85 | lambda$retry$7 | MathMutator (− → +) | SURVIVED | test: CardPaymentsSurvivorsTest#T_12_15_aRetryPastTheMaximumReportsTheAttemptsMade |
| CardPayments:76 | lambda$retry$7 | VoidMethodCall (IdempotentOperation.lock) | SURVIVED | test: CardPaymentsSurvivorsTest#T_12_15_aRetryLocksItsKeyBeforeReplayingTheStoredCommand |
| CardPayments:142 | lambda$settle$13 | BooleanTrueReturnVals | SURVIVED | test: CardPaymentsSurvivorsTest#T_12_16_anExpiredCardFailureInvalidatesTheChargedCardAndReconcilesOnlyItsCommand |
| CardPayments:68 | prepare | VoidMethodCall (collections.resolve FAILED) | SURVIVED | test: CardPaymentsSurvivorsTest#T_12_15_anInvoiceWithoutAUsableCardIsSkippedUnderTheKeysLock |
| CardPayments:101 | queue | VoidMethodCall (collections.resolve SUBMITTED) | SURVIVED | test: CardPaymentsSurvivorsTest#T_12_15_aPendingInvoiceIsChargedOnItsCreatedAttemptWhichBecomesSubmitted |
| CardPayments:158 | resolve | NegateConditionals (idx 87, `success ? null : code`) | SURVIVED | test: CardPaymentsSurvivorsTest#T_12_16_anExpiredCardFailureInvalidatesTheChargedCardAndReconcilesOnlyItsCommand |
| CardPayments:149 | resolve | BooleanTrueReturnVals | NO_COVERAGE | test: CardPaymentsSurvivorsTest#T_12_15_aSettlementOfACancelledInvoiceChangesNothing |
| CardPayments:157 | resolve | BooleanTrueReturnVals | SURVIVED | test: CardPaymentsSurvivorsTest#T_12_15_aRepeatedFailureOfAFailedAttemptChangesNothing |
| CardPayments:74 | retry | VoidMethodCall (provider.require) | SURVIVED | test: CardPaymentsSurvivorsTest#T_12_15_aRetryIsNotQueuedWithoutAnOffSessionProvider |
| CardPayments:140 | settle | VoidMethodCall (collections.submitted) | SURVIVED | test: CardPaymentsSurvivorsTest#T_12_16_anExpiredCardFailureInvalidatesTheChargedCardAndReconcilesOnlyItsCommand |
