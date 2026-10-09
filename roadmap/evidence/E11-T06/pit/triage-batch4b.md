# E11-T06 batch 4 (part b): survivor triage, payments classes other than PaymentRefunds / PaymentCheckouts

Source: `batch4-not-killed.tsv` (43 rows). Tests are staged under `.local/e11-t06-staged-tests-4/src/test/java/com/agilityhub/core/payments/application/`.
Test classes: `PackBalanceServiceSurvivorsTest` (PBS), `PaymentProviderRegistrySurvivorsTest` (PPR), `PaymentProviderSurvivorsTest` (PP),
`PaymentRetryPolicySurvivorsTest` (PRP), `PaymentPrivacySurvivorsTest`, `PaymentBookingCancellationsSurvivorsTest`, `PaymentAuditsSurvivorsTest`,
`PackViewsSurvivorsTest`, `PackAuditLoaderSurvivorsTest`.

| Class:line | Method | Mutator | Status | Decision |
|---|---|---|---|---|
| PackBalanceService:143 | adjust | ConditionalsBoundary (`remaining < 0` → `<= 0`) | SURVIVED | test: PackBalanceServiceSurvivorsTest#T_12_07_anAdjustmentDownToExactlyZeroIsAllowedAndPublishesDeltaAndReason |
| PackBalanceService:90 | balance | EmptyObjectReturnVals (Optional.empty) | SURVIVED | test: PackBalanceServiceSurvivorsTest#T_12_07_aPackMemberWithoutAnyPackHasAnEmptyBalanceNotNone |
| PackBalanceService:119 | consume | NegateConditionals (`if (low)`) | SURVIVED | test: PackBalanceServiceSurvivorsTest#T_12_07_aConsumptionPublishesPackConsumedWithItsBookingAndBumpsTheVersion |
| PackBalanceService:118 | consume | VoidMethodCall (publish PackConsumed) | SURVIVED | test: PackBalanceServiceSurvivorsTest#T_12_07_aConsumptionPublishesPackConsumedWithItsBookingAndBumpsTheVersion |
| PackBalanceService:48 | lambda$get$0 | NullReturnVals | NO_COVERAGE | test: PackBalanceServiceSurvivorsTest#T_12_07_anUnknownPackIsNotFound |
| PackBalanceService:53 | lambda$open$1 | NullReturnVals | NO_COVERAGE | test: PackBalanceServiceSurvivorsTest#T_12_16_aPaymentOfAnUnknownMemberIsNotFound |
| PackBalanceService:130 | lambda$refund$9 | BooleanTrueReturnVals | SURVIVED | reason: unreachable with real data — `forBooking` matches `movements.bookingId` (BillingDocuments.java:361-362); only CONSUME/REFUND movements carry a `bookingId` (PackBalanceService.java:114,131; OPEN/ADJUST/EXPIRE pass null, lines 44,72,147,159; MemberPlanChangeService.java:36 copies the movements unchanged; no other writer of `pack_balances`), a REFUND is appended only to a pack that already holds that booking's CONSUME (line 130 before 131) and returns at line 129, so the found pack always holds the booking's CONSUME and `noneMatch` is never true |
| PackBalanceService:47 | list | NegateConditionals | SURVIVED | test: PackBalanceServiceSurvivorsTest#T_12_22_withPacksOnTheBalancesOfADogAreListed |
| PackBalanceService:68 | open | ConditionalsBoundary (`total <= 0` → `< 0`) | SURVIVED | test: PackBalanceServiceSurvivorsTest#T_12_07_aPackOfZeroSessionsIsRejected |
| PackBalanceService:52 | open | NegateConditionals (index 11, `forPayment(...).isPresent()`) | SURVIVED | test: PackBalanceServiceSurvivorsTest#T_12_16_aPaidPackPaymentOpensOnePackOnlyOnce |
| PackBalanceService:40 | openMigrated | ConditionalsBoundary (index 32, `consumed < 0`) | SURVIVED | test: PackBalanceServiceSurvivorsTest#T_18_15_aMigratedPackWithNothingConsumedOpensActiveWithoutAnExpiryInstant |
| PackBalanceService:40 | openMigrated | ConditionalsBoundary (index 34, `remaining < 0`) | SURVIVED | test: PackBalanceServiceSurvivorsTest#T_18_15_aFullyConsumedMigratedPackOpensClosedAndAnExpiredOneCarriesItsInstant |
| PackBalanceService:45 | openMigrated | NegateConditionals (`state == EXPIRED`) | SURVIVED | test: PackBalanceServiceSurvivorsTest#T_18_15_aMigratedPackWithNothingConsumedOpensActiveWithoutAnExpiryInstant |
| PackBalanceService:190 | publish | NegateConditionals (`bookingId != null`) | SURVIVED | test: PackBalanceServiceSurvivorsTest#T_12_07_aConsumptionPublishesPackConsumedWithItsBookingAndBumpsTheVersion (also #T_12_07_anOpeningEventCarriesNoBookingKey) |
| PackBalanceService:191 | publish | VoidMethodCall (`putAll(extra)`) | SURVIVED | test: PackBalanceServiceSurvivorsTest#T_12_07_anAdjustmentDownToExactlyZeroIsAllowedAndPublishesDeltaAndReason |
| PackBalanceService:130 | refund | EmptyObjectReturnVals ("") | NO_COVERAGE | reason: unreachable with real data — the `return null` of line 130 never runs: a pack found by `movements.bookingId` (BillingDocuments.java:361-362) always holds that booking's CONSUME (only CONSUME/REFUND movements carry a `bookingId`, PackBalanceService.java:114,131, and no other writer adds movements, MemberPlanChangeService.java:36; a REFUND is appended only after line 130 found the CONSUME, and returns at line 129) |
| PackBalanceService:135 | refund | EmptyObjectReturnVals ("") | SURVIVED | test: PackBalanceServiceSurvivorsTest#T_12_07_aRefundAnswersTheIdOfItsRefundMovement |
| PackBalanceService:173 | warnExpiry | MathMutator (`version + 1` → `- 1`) | SURVIVED | test: PackBalanceServiceSurvivorsTest#T_15_19_aPackExpiringWithinTheWindowIsWarnedOnceWithTheNextVersion |
| PaymentProviderRegistry:45 | callTimeout | NullReturnVals | SURVIVED | test: PaymentProviderRegistrySurvivorsTest#T_12_15_theRegistryDeclaresTheContractsCallTimeout |
| PaymentProviderRegistry:42 | complete | VoidMethodCall | NO_COVERAGE | test: PaymentProviderRegistrySurvivorsTest#T_12_16_aCompletedSessionIsHandedToTheAdapter |
| PaymentProviderRegistry:39 | parseWebhook | NullReturnVals | NO_COVERAGE | test: PaymentProviderRegistrySurvivorsTest#T_12_15_aWebhookIsParsedByTheAdapter |
| PaymentProviderRegistry:37 | refund | NullReturnVals | NO_COVERAGE | test: PaymentProviderRegistrySurvivorsTest#T_12_17_aRefundAnswersTheAdaptersResult |
| PaymentRetryPolicy:53 | execute | NegateConditionals (`if (tx.run(...))`) | SURVIVED | test: PaymentRetryPolicySurvivorsTest#T_12_15_anExhaustingFailureUnderTheClaimRunsTheTerminalTransitionAndWarnsOnce (WARN captured with a logback ListAppender) |
| PaymentRetryPolicy:59 | lambda$execute$4 | NegateConditionals (`if (terminal)`) | SURVIVED | test: PaymentRetryPolicySurvivorsTest#T_12_15_aFailureWithAttemptsLeftNeitherRunsTheTerminalTransitionNorWarns |
| PaymentRetryPolicy:54 | lambda$execute$4 | BooleanTrueReturnVals (lost claim answers true) | SURVIVED | test: PaymentRetryPolicySurvivorsTest#T_12_15_aFailureAfterTheClaimWasLostCountsNothingAndDoesNotWarn |
| PackBalanceService:166 | warnExpiry | BooleanTrueReturnVals | NO_COVERAGE | test: PackBalanceServiceSurvivorsTest#T_15_19_withPacksOffNothingIsWarned |
| PackBalanceService:169 | warnExpiry | BooleanTrueReturnVals | NO_COVERAGE | test: PackBalanceServiceSurvivorsTest#T_15_19_aPackExpiringAfterTheWindowIsNotWarned (also the second call of #T_15_19_aPackExpiringWithinTheWindowIsWarnedOnceWithTheNextVersion) |
| PackBalanceService:184 | write | MathMutator (`version + 1` → `- 1`) | SURVIVED | test: PackBalanceServiceSurvivorsTest#T_12_07_aConsumptionPublishesPackConsumedWithItsBookingAndBumpsTheVersion |
| PaymentProvider:35 | disabled | NullReturnVals | SURVIVED | test: PaymentProviderSurvivorsTest#T_12_15_anUndeclaredOffSessionPaymentIsRefusedAsNotSubmitted |
| PaymentProvider:29 | refund | NullReturnVals | NO_COVERAGE | test: PaymentProviderSurvivorsTest#T_12_17_theRefundWithAnOperationIdAnswersThePlainRefund |
| PaymentProvider:25 | supports | BooleanTrueReturnVals | SURVIVED | test: PaymentProviderSurvivorsTest#T_12_15_aProviderSupportsNoCapabilityItDoesNotDeclare |
| PaymentPrivacy:28 | lambda$execute$1 | VoidMethodCall (`execution.fence()`) | SURVIVED | test: PaymentPrivacySurvivorsTest#E11_T06_aClaimLostDuringTheProviderCallNeverCompletesTheCommand |
| PaymentPrivacy:28 | lambda$execute$1 | VoidMethodCall (`operations.completed`) | SURVIVED | test: PaymentPrivacySurvivorsTest#E11_T06_aForgottenCustomerCompletesItsCommandUnderTheHeldClaim |
| PaymentBookingCancellations:21 | eventType | EmptyObjectReturnVals ("") | SURVIVED | test: PaymentBookingCancellationsSurvivorsTest#T_12_17_theRefundHandlerSubscribesToBookingCancelled |
| PaymentRetryPolicy:60 | lambda$execute$4 | BooleanTrueReturnVals (`return terminal` → true) | SURVIVED | test: PaymentRetryPolicySurvivorsTest#T_12_15_aFailureWithAttemptsLeftNeitherRunsTheTerminalTransitionNorWarns |
| PaymentRetryPolicy:67 | warnRefund | VoidMethodCall (`warning`) | SURVIVED | test: PaymentRetryPolicySurvivorsTest#T_12_17_aRefundReconciliationStatusIsLoggedAsAWarningWithItsId |
| PaymentAudits:20 | recorded | EmptyObjectReturnVals (emptyMap) | SURVIVED | test: PaymentAuditsSurvivorsTest#E11_T06_theRecordedUpfrontPaymentAuditDetailsCarryTheAmountPaid |
| PaymentAudits:13 | started | EmptyObjectReturnVals (emptyMap) | SURVIVED | test: PaymentAuditsSurvivorsTest#T_12_15_theCardChargesAuditDetailsCarryTheRunAndItsCounters |
| PackViews:16 | lambda$view$0 | EmptyObjectReturnVals ("") | SURVIVED | test: PackViewsSurvivorsTest#T_12_07_aPackViewCarriesItsPlanNameInTheRequestLocale |
| PaymentProviderRegistry$1:51 | callTimeout | NullReturnVals | NO_COVERAGE | test: PaymentProviderRegistrySurvivorsTest#T_12_15_aDisabledProviderSupportsNothingAndRefusesWithItsCode |
| PaymentProviderRegistry$1:47 | error | NullReturnVals | NO_COVERAGE | test: PaymentProviderRegistrySurvivorsTest#T_12_15_aDisabledProviderSupportsNothingAndRefusesWithItsCode |
| PackAuditLoader:11 | entityType | EmptyObjectReturnVals ("") | SURVIVED | test: PackAuditLoaderSurvivorsTest#T_12_07_theLoaderServesTheAuditedPackBalanceType |
| PaymentProviderRegistry:34 | supports | BooleanTrueReturnVals | SURVIVED | test: PaymentProviderRegistrySurvivorsTest#T_12_15_aDisabledProviderSupportsNothingAndRefusesWithItsCode |

Totals: 43 rows, 41 with a test, 2 with a reason. No production bug found.
