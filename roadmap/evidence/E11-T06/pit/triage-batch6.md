# E11-T06 PIT batch 6 survivor triage (`com.agilityhub.core.payments.api`)

Source: `batch6-not-killed.tsv` (55 rows) and `batch6-mutations.xml` (the `<index>` picks which conditional a line mutates). The staged tests live in
`.local/e11-t06-staged-tests-6/src/test/java/com/agilityhub/core/payments/api/` (to be moved into `src/test/java`). Most of these mutants are
guard calls (`BillingContractAccess.*`) that the ITs cannot tell apart, because the service behind the guard throws the same 404/400 for another
club's resource. The unit tests mock the guard to throw and assert that the route fails with that code and never reaches its service. Without the
guard the route either returns a value or fails with an NPE or a parse error.

| Class:line | Method | Mutator | Status | Decision |
|---|---|---|---|---|
| UpfrontPaymentsController:81 | lambda$refundUpfrontPayment$3 | NullReturnVals | SURVIVED | test: UpfrontPaymentsControllerSurvivorsTest#T_12_17_aRefundAnswersTheAcceptedRefundOfItsKeyedTransaction |
| UpfrontPaymentsController:50 | lambda$upfrontPayments$1 | NullReturnVals | SURVIVED | test: UpfrontPaymentsControllerSurvivorsTest#E11_T06_theListPassesItsStatusFilterAndAnswersEveryPayment |
| UpfrontPaymentsController:63 | recordUpfrontPayment | VoidMethodCall (mutableMember) | SURVIVED | test: UpfrontPaymentsControllerSurvivorsTest#T_12_21_anErasedMemberRecordsNoPayment |
| PackBalancesController:82 | adjustPackBalance | VoidMethodCall (pack) | SURVIVED | test: PackBalancesControllerSurvivorsTest#T_12_21_anotherClubsPackIsNotFoundBeforeItIsAdjusted |
| PackBalancesController:71 | lambda$openPackBalance$2 | NullReturnVals | SURVIVED | test: PackBalancesControllerSurvivorsTest#T_12_07_aPackOpenedByHandAnswersTheOpenedPack |
| PackBalancesController:69 | openPackBalance | VoidMethodCall (mutableMember) | SURVIVED | test: PackBalancesControllerSurvivorsTest#T_12_21_anErasedMemberOrAnotherMembersDogOpensNoPack |
| PackBalancesController:70 | openPackBalance | VoidMethodCall (memberDog) | SURVIVED | test: PackBalancesControllerSurvivorsTest#T_12_21_anErasedMemberOrAnotherMembersDogOpensNoPack |
| BillingViews:43 | collection | NegateConditionals (idx 31, `refunds() == null`) | SURVIVED | test: BillingViewsSurvivorsTest#T_12_17_aCollectionPublishesEachOfItsRefunds |
| BillingViews:43 | lambda$collection$1 | NullReturnVals | SURVIVED | test: BillingViewsSurvivorsTest#T_12_17_aCollectionPublishesEachOfItsRefunds |
| BillingViews:95 | remittance | NegateConditionals (idx 36, name: `creditor == null`) | SURVIVED | test: BillingViewsSurvivorsTest#T_12_11_aRemittancePublishesItsCreditorWithTheIbanMasked |
| BillingViews:95 | remittance | NegateConditionals (idx 39, `creditor.name() == null`) | SURVIVED | test: BillingViewsSurvivorsTest#T_12_11_aRemittancePublishesItsCreditorWithTheIbanMasked |
| BillingViews:95 | remittance | NegateConditionals (idx 51, id: `creditor == null`) | SURVIVED | test: BillingViewsSurvivorsTest#T_12_11_aRemittancePublishesItsCreditorWithTheIbanMasked |
| BillingViews:95 | remittance | NegateConditionals (idx 54, `creditor.id() == null`) | SURVIVED | test: BillingViewsSurvivorsTest#T_12_11_aRemittancePublishesItsCreditorWithTheIbanMasked |
| BillingViews:96 | remittance | NegateConditionals (idx 78, bic: `creditor == null`) | SURVIVED | test: BillingViewsSurvivorsTest#T_12_11_aRemittancePublishesItsCreditorWithTheIbanMasked |
| BillingController:61 | billingPeriod | VoidMethodCall (tenant) | SURVIVED | test: BillingControllerSurvivorsTest#T_12_21_theMonthIsReadOnlyInsideTheCallersTenant |
| BillingController:115 | billingRun | VoidMethodCall (run) | SURVIVED | test: BillingControllerSurvivorsTest#T_12_21_anotherClubsRunIsNotFoundBeforeItIsReadOrRolledBack |
| BillingController:102 | createBillingRun | VoidMethodCall (month) | SURVIVED | test: BillingControllerSurvivorsTest#E11_T06_aRunOfAMalformedMonthIsAValidationErrorBeforeAnythingIsGenerated |
| BillingController:172 | exportAccounting | VoidMethodCall (tenant) | SURVIVED | test: BillingControllerSurvivorsTest#T_12_19_theAccountingExportChecksTheTenantAndTheFormatBeforeExporting |
| BillingController:174 | exportAccounting | VoidMethodCall (oneOf) | SURVIVED | test: BillingControllerSurvivorsTest#T_12_19_theAccountingExportChecksTheTenantAndTheFormatBeforeExporting |
| BillingController:148 | rollbackBillingRun | VoidMethodCall (run) | SURVIVED | test: BillingControllerSurvivorsTest#T_12_21_anotherClubsRunIsNotFoundBeforeItIsReadOrRolledBack |
| BillingController:78 | simulateBilling | VoidMethodCall (tenant) | SURVIVED | test: BillingControllerSurvivorsTest#T_12_21_aSimulationRunsOnlyInsideTheCallersTenant |
| MyBillingController:119 | lambda$myPackBalances$1 | NullReturnVals | SURVIVED | test: MyBillingControllerSurvivorsTest#T_12_07_thePackScreenListsEveryPackOfTheCaller |
| MyBillingController:71 | myInvoice | VoidMethodCall (ownInvoice) | SURVIVED | test: MyBillingControllerSurvivorsTest#T_13_24_anotherMembersInvoiceIsNotFoundBeforeItIsReadOrRendered |
| MyBillingController:85 | myInvoiceDocument | VoidMethodCall (ownInvoice) | SURVIVED | test: MyBillingControllerSurvivorsTest#T_13_24_anotherMembersInvoiceIsNotFoundBeforeItIsReadOrRendered |
| MyBillingController:61 | myInvoices | Math (long + → −) | SURVIVED | test: MyBillingControllerSurvivorsTest#T_12_20_theInvoicePageCountsItsPagesAsTheCeilingOfTotalOverSize |
| MyBillingController:61 | myInvoices | Math (long − → +) | SURVIVED | test: MyBillingControllerSurvivorsTest#T_12_20_theInvoicePageCountsItsPagesAsTheCeilingOfTotalOverSize |
| InvoicesController:178 | cancelInvoice | VoidMethodCall (invoice) | SURVIVED | test: InvoicesControllerSurvivorsTest#T_12_21_anotherClubsInvoiceIsNotFoundBeforeAnyActionRuns |
| InvoicesController:96 | createManualInvoice | VoidMethodCall (mutableMember) | SURVIVED | test: InvoicesControllerSurvivorsTest#T_12_21_anErasedMembersManualInvoiceIsRefusedBeforeItIsIssued |
| InvoicesController:81 | invoice | VoidMethodCall (invoice) | SURVIVED | test: InvoicesControllerSurvivorsTest#T_12_21_anotherClubsInvoiceIsNotFoundBeforeAnyActionRuns |
| InvoicesController:191 | invoiceDocument | VoidMethodCall (invoice) | SURVIVED | test: InvoicesControllerSurvivorsTest#T_12_21_anotherClubsInvoiceIsNotFoundBeforeAnyActionRuns |
| InvoicesController:69 | invoices | VoidMethodCall (tenant) | SURVIVED | test: InvoicesControllerSurvivorsTest#T_12_21_theInvoiceListRunsOnlyInsideTheCallersTenant |
| InvoicesController:167 | lambda$refundInvoice$7 | NullReturnVals | SURVIVED | test: InvoicesControllerSurvivorsTest#T_12_17_aRefundAnswersTheAcceptedRefundOfItsKeyedTransaction |
| InvoicesController:135 | markInvoiceFailed | VoidMethodCall (invoice) | SURVIVED | test: InvoicesControllerSurvivorsTest#T_12_21_anotherClubsInvoiceIsNotFoundBeforeAnyActionRuns |
| InvoicesController:111 | markInvoicePaid | VoidMethodCall (invoice) | SURVIVED | test: InvoicesControllerSurvivorsTest#T_12_21_anotherClubsInvoiceIsNotFoundBeforeAnyActionRuns |
| InvoicesController:121 | markInvoicesPaid | VoidMethodCall (invoices) | SURVIVED | test: InvoicesControllerSurvivorsTest#T_12_21_aBulkPaymentNamingAnotherClubsInvoiceIsNotFoundAndPaysNothing |
| RemittancesController:97 | json | NullReturnVals | SURVIVED | test: RemittancesControllerSurvivorsTest#T_12_11_theStoredAnswerOfASubmissionIsThePublishedRemittanceWithoutTheIban |
| RemittancesController:92 | lambda$submitRemittance$1 | NullReturnVals | SURVIVED | test: RemittancesControllerSurvivorsTest#T_12_11_theStoredAnswerOfASubmissionIsThePublishedRemittanceWithoutTheIban |
| RemittancesController:64 | remittance | VoidMethodCall (remittance) | SURVIVED | test: RemittancesControllerSurvivorsTest#T_12_21_anotherClubsRemittanceIsNotFoundBeforeItIsReadLinkedOrSubmitted |
| RemittancesController:76 | remittanceFile | VoidMethodCall (remittance) | SURVIVED | test: RemittancesControllerSurvivorsTest#T_12_21_anotherClubsRemittanceIsNotFoundBeforeItIsReadLinkedOrSubmitted |
| RemittancesController:53 | remittances | VoidMethodCall (tenant) | SURVIVED | test: RemittancesControllerSurvivorsTest#T_12_21_theRemittanceListRunsOnlyInsideTheCallersTenant |
| RemittancesController:91 | submitRemittance | VoidMethodCall (remittance) | SURVIVED | test: RemittancesControllerSurvivorsTest#T_12_21_anotherClubsRemittanceIsNotFoundBeforeItIsReadLinkedOrSubmitted |
| PackBalancesController:56 | packBalances | EmptyObjectReturnVals | SURVIVED | test: PackBalancesControllerSurvivorsTest#T_12_07_theListAnswersEveryPackOfTheMember |
| InvoicesController:164 | refundInvoice | VoidMethodCall (invoice) | SURVIVED | test: InvoicesControllerSurvivorsTest#T_12_21_anotherClubsInvoiceIsNotFoundBeforeTheProviderIsCalled |
| InvoicesController:167 | refundInvoice | NullReturnVals | SURVIVED | test: InvoicesControllerSurvivorsTest#T_12_17_aRefundAnswersTheAcceptedRefundOfItsKeyedTransaction |
| InvoicesController:148 | retryInvoice | VoidMethodCall (invoice) | SURVIVED | test: InvoicesControllerSurvivorsTest#T_12_21_anotherClubsInvoiceIsNotFoundBeforeTheProviderIsCalled |
| InvoicesController:151 | retryInvoice | NullReturnVals | SURVIVED | test: InvoicesControllerSurvivorsTest#T_12_15_aRetryChargesTheCardAndAnswersTheInvoiceOfItsKeyedTransaction |
| UpfrontPaymentsController:78 | refundUpfrontPayment | VoidMethodCall (upfrontPayment) | SURVIVED | test: UpfrontPaymentsControllerSurvivorsTest#T_12_21_anotherClubsPaymentIsNotFoundBeforeTheRefund |
| UpfrontPaymentsController:81 | refundUpfrontPayment | NullReturnVals | SURVIVED | test: UpfrontPaymentsControllerSurvivorsTest#T_12_17_aRefundAnswersTheAcceptedRefundOfItsKeyedTransaction |
| UpfrontPaymentsController:50 | upfrontPayments | NegateConditionals (`status == null`) | SURVIVED | test: UpfrontPaymentsControllerSurvivorsTest#E11_T06_theListPassesItsStatusFilterAndAnswersEveryPayment |
| UpfrontPaymentsController:50 | upfrontPayments | NullReturnVals | SURVIVED | test: UpfrontPaymentsControllerSurvivorsTest#E11_T06_theListPassesItsStatusFilterAndAnswersEveryPayment |
| MemberBillingController:51 | cardSetupLink | NullReturnVals | SURVIVED | test: MemberBillingControllerSurvivorsTest#T_12_16_theCardSetupLinkAnswersTheSetupSessionUrl |
| MyBillingController:61 | myInvoices | Math (long / → *) | SURVIVED | test: MyBillingControllerSurvivorsTest#T_12_20_theInvoicePageCountsItsPagesAsTheCeilingOfTotalOverSize |
| MyBillingController:58 | myInvoices | VoidMethodCall (tenant) | SURVIVED | test: MyBillingControllerSurvivorsTest#T_12_21_theInvoicesAreReadOnlyInsideTheCallersTenant |
| MyBillingController:119 | myPackBalances | EmptyObjectReturnVals | SURVIVED | test: MyBillingControllerSurvivorsTest#T_12_07_thePackScreenListsEveryPackOfTheCaller |
| CheckoutContracts$CheckoutSessionRequest:28 | extended | NegateConditionals (idx 8, `upfrontPaymentIds != null`) | RUN_ERROR | rerun: E3ContractIT#T_04_25_allElevenRoutesEnforceRolesAndTenantBoundaries (invocation #7, a plain signup checkout with both fields null: the mutant makes `extended()` true and sends it down the S12 path; the same invocation killed idx 5 and the true-return mutant of this line). E8ContractIT#T_12_21_T_13_24_theCheckoutExtensionsAuthorizeAndCheckTheirReferencesBeforeTheStub (`upfrontPaymentIds` alone → 404) is a second killer. RUN_ERROR is an infrastructure failure, not a survivor. |

Totals: 54 rows → test (8 staged classes, 31 methods), 0 rows → reason, 1 row → rerun. No production bug found.
