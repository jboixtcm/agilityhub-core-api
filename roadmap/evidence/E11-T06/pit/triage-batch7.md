# E11-T06 step 8 · PIT batch 7 triage (`com.agilityhub.core.payments.persistence`)

Input: `batch7-not-killed.tsv` (33 rows). Staged tests (to move into `src/test/java/com/agilityhub/core/payments/persistence/`):
`BillingDocumentsSurvivorsTest`, `PaymentOperationRepositorySurvivorsTest`, `CollectionSurvivorsTest`, `PaymentRetryStateSurvivorsTest`,
`SignupCheckoutRepositorySurvivorsTest`, `StripeInboxSurvivorsTest` (Mockito mock of `MongoTemplate`, no Spring/Mongo). Rows: 33 with a test, 0 with a reason.

| Class:line | Method | Mutator | Status | Decision |
|---|---|---|---|---|
| PaymentOperationRepository:37 | forResult | EmptyObjectReturnVals (Optional.empty) | SURVIVED | test: PaymentOperationRepositorySurvivorsTest#T_12_17_aRefundWebhookFindsItsCommandByResultId |
| BillingDocuments$BillingRunRepository:308 | charging | BooleanTrueReturnVals | SURVIVED | test: BillingDocumentsSurvivorsTest#T_12_15_chargingIsFalseWhenTheRunIsNeitherGeneratedNorCharging |
| BillingDocuments$BillingRunRepository:297 | lambda$cardRequest$0 | BooleanTrueReturnVals | SURVIVED | test: BillingDocumentsSurvivorsTest#T_12_15_aStoredCardRequestIsTheOneWithTheSameReference |
| BillingDocuments$BillingRunRepository:321 | latest | NegateConditionals | SURVIVED | test: BillingDocumentsSurvivorsTest#T_12_13_theMonthsLiveRunWinsOverItsRolledBackOne (also #T_12_13_withoutALiveRunTheLatestRolledBackOneIsShown) |
| BillingDocuments$BillingRunRepository:322 | latest | EmptyObjectReturnVals (Optional.empty) | SURVIVED | test: BillingDocumentsSurvivorsTest#T_12_13_withoutALiveRunTheLatestRolledBackOneIsShown |
| Collection:42 | publishedReference | EmptyObjectReturnVals ("") | SURVIVED | test: CollectionSurvivorsTest#E11_T06_theStoredProviderReferenceIsPublishedAsIs |
| PaymentRetryState:22 | failed | MathMutator (<< → >>) | SURVIVED | test: PaymentRetryStateSurvivorsTest#E11_T06_theSecondFailureWaitsTwentySecondsAndIsNotExhausted |
| PaymentRetryState:18 | failed | BooleanTrueReturnVals | NO_COVERAGE | test: PaymentRetryStateSurvivorsTest#E11_T06_aProcessedOrMissingCheckpointIsNotExhausted |
| PaymentRetryState:24 | failed | BooleanTrueReturnVals | SURVIVED | test: PaymentRetryStateSurvivorsTest#E11_T06_theSecondFailureWaitsTwentySecondsAndIsNotExhausted (also StripeInboxSurvivorsTest#T_12_15_aFailedEventBelowTheMaximumIsRetried) |
| SignupCheckoutRepository:38 | providerSession | NegateConditionals | NO_COVERAGE | test: SignupCheckoutRepositorySurvivorsTest#T_12_16_theStoredProviderSessionIsReadBack |
| BillingDocuments$InvoiceRepository:75 | ensureIndexes | NegateConditionals (index 29, `getName().equals`) | SURVIVED | test: BillingDocumentsSurvivorsTest#T_12_13_onlyTheUnconditionalLegacyNumberIndexIsDropped |
| BillingDocuments$InvoiceRepository:75 | ensureIndexes | NegateConditionals (index 32, `getPartialFilterExpression() == null`) | SURVIVED | test: BillingDocumentsSurvivorsTest#T_12_13_onlyTheUnconditionalLegacyNumberIndexIsDropped (also #T_12_13_thePartialNumberGuardIsKept) |
| BillingDocuments$InvoiceRepository:75 | ensureIndexes | VoidMethodCall (dropIndex) | NO_COVERAGE | test: BillingDocumentsSurvivorsTest#T_12_13_onlyTheUnconditionalLegacyNumberIndexIsDropped |
| BillingDocuments$InvoiceRepository:105 | ofMembers | MathMutator (* → /) | SURVIVED | test: BillingDocumentsSurvivorsTest#T_12_20_aMembersInvoicePageSkipsPageTimesSize |
| PaymentOperationRepository:55 | refundSettled | NegateConditionals | SURVIVED | test: PaymentOperationRepositorySurvivorsTest#T_12_17_aRefundIsSettledOnlyOnce |
| PaymentOperationRepository:54 | refundSettled | BooleanTrueReturnVals | SURVIVED | test: PaymentOperationRepositorySurvivorsTest#T_12_17_aRefundIsSettledOnlyOnce |
| PaymentOperationRepository:68 | reverseRefund | BooleanTrueReturnVals | SURVIVED | test: PaymentOperationRepositorySurvivorsTest#T_12_17_aReversedRefundReportsWhetherItWasStillThere |
| BillingDocuments$PendingChargeRepository:404 | bill | BooleanTrueReturnVals | SURVIVED | test: BillingDocumentsSurvivorsTest#T_12_09_billingIsFalseForAnAlreadyBilledOrVoidedCharge |
| BillingDocuments$PendingChargeRepository:409 | unbill | PrimitiveReturns (0) | SURVIVED | test: BillingDocumentsSurvivorsTest#T_12_13_unbillReturnsHowManyChargesWentBack (the only caller, BillingRunService:229, discards the count) |
| BillingDocuments$PendingChargeRepository:415 | voided | NegateConditionals | SURVIVED | test: BillingDocumentsSurvivorsTest#T_12_08_voidingIsTrueBeforeBillingAndFalseOnceBilled |
| BillingDocuments$PendingChargeRepository:414 | voided | BooleanTrueReturnVals | SURVIVED | test: BillingDocumentsSurvivorsTest#T_12_08_voidingIsTrueBeforeBillingAndFalseOnceBilled (PendingChargeService:65-66 discards it and re-reads the charge) |
| BillingDocuments$RemittanceRepository:257 | rollBack | BooleanTrueReturnVals | SURVIVED | test: BillingDocumentsSurvivorsTest#T_12_13_aRemittanceRollbackIsFalseWhenItIsNoLongerGenerated |
| BillingDocuments$RemittanceRepository:262 | submit | BooleanTrueReturnVals | SURVIVED | test: BillingDocumentsSurvivorsTest#T_12_30_aSubmissionIsFalseWhenTheRemittanceIsNoLongerGenerated |
| BillingDocuments$BillingRunRepository:326 | rollBack | BooleanTrueReturnVals | SURVIVED | test: BillingDocumentsSurvivorsTest#T_12_13_aRunRollbackIsFalseWhenTheRunMovedMeanwhile |
| BillingDocuments$PackBalanceRepository:359 | forPayment | EmptyObjectReturnVals (Optional.empty) | SURVIVED | test: BillingDocumentsSurvivorsTest#T_12_16_thePackOfAnUpfrontPaymentIsFound |
| StripeInbox:36 | failed | BooleanTrueReturnVals | SURVIVED | test: StripeInboxSurvivorsTest#T_12_15_aFailedEventBelowTheMaximumIsRetried |
| BillingDocuments$InvoiceRepository:172 | transition | BooleanTrueReturnVals | SURVIVED | test: BillingDocumentsSurvivorsTest#T_12_14_aTransitionIsFalseWhenTheInvoiceMovedMeanwhile |
| BillingDocuments:56 | present | NullReturnVals | SURVIVED | test: BillingDocumentsSurvivorsTest#E11_T06_theProviderReferenceIndexIsUniqueOnlyWhenPresent (on Mongo also E8PersistenceIT#T_12_10_T_12_11_T_12_15_theElevenCollectionsDeclareTheirIndexesAndTheThreeConcurrencyGuards) |
| BillingDocuments$CollectionRepository:228 | forInvoices | EmptyObjectReturnVals (emptyList) | SURVIVED | test: BillingDocumentsSurvivorsTest#T_12_13_theAttemptsOfTheInvoicesAreReturned |
| SignupCheckoutRepository:38 | providerSession | EmptyObjectReturnVals (Optional.empty) | NO_COVERAGE | test: SignupCheckoutRepositorySurvivorsTest#T_12_16_theStoredProviderSessionIsReadBack |
| SignupCheckoutRepository:24 | standaloneCardSetup | BooleanFalseReturnVals | SURVIVED | test: SignupCheckoutRepositorySurvivorsTest#T_12_31_aStandaloneCardSetupIsWhatMongoAnswers |
| SignupCheckoutRepository:24 | standaloneCardSetup | BooleanTrueReturnVals | SURVIVED | test: SignupCheckoutRepositorySurvivorsTest#T_12_31_aStandaloneCardSetupIsWhatMongoAnswers |
| Collection:45 | publishedReference | EmptyObjectReturnVals ("") | SURVIVED | test: CollectionSurvivorsTest#E11_T06_anAttemptWithoutAnyReferencePublishesNone |
