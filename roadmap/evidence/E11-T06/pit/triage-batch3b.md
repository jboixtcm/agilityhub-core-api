# E11-T06 batch 3 survivor triage (b): payments classes other than CardPayments and CheckoutService

Input: `batch3-not-killed.tsv`, the 55 rows whose class is not `CardPayments` or `CheckoutService`, in TSV order. Every staged test
class is in `.local/e11-t06-staged-tests-3/src/test/java/com/agilityhub/core/payments/application/`. Each test is a pure unit test
with mocked collaborators, and the main agent still has to compile it and run it under PIT. Conditional indexes come from
`batch3-mutations.xml`. Where a line has more than one conditional, the test fails under each of them.

| Class:line | Method | Mutator | Status | Decision |
|---|---|---|---|---|
| FakeProviderCommand:27 | name | EmptyObjectReturnVals | NO_COVERAGE | test: FakeProviderCommandSurvivorsTest#T_12_16_theCommandIsNamedAndReportsTheDeliveredCompletion |
| InvoiceActions:123 | createManual | VoidMethodCall (requireCurrency) | SURVIVED | test: InvoiceActionsSurvivorsTest#T_12_05_aManualLineInAnotherCurrencyIsRefusedBeforeANumberIsTaken |
| InvoiceActions:119 | lambda$createManual$6 | NullReturnVals | NO_COVERAGE | test: InvoiceActionsSurvivorsTest#T_12_21_anUnknownReceiptOrMemberIsNotFound |
| InvoiceActions:162 | lambda$current$8 | NullReturnVals | NO_COVERAGE | test: InvoiceActionsSurvivorsTest#T_12_21_anUnknownReceiptOrMemberIsNotFound |
| InvoiceActions:61 | lambda$detail$0 | NullReturnVals | NO_COVERAGE | test: InvoiceActionsSurvivorsTest#T_12_21_anUnknownReceiptOrMemberIsNotFound |
| InvoiceActions:85 | lambda$markFailed$4 | NegateConditionals | SURVIVED | test: InvoiceActionsSurvivorsTest#T_12_14_onlyASepaAttemptMakesAPaidReceiptReturnable |
| InvoiceActions:85 | lambda$markFailed$4 | BooleanTrueReturnVals | SURVIVED | test: InvoiceActionsSurvivorsTest#T_12_14_onlyASepaAttemptMakesAPaidReceiptReturnable |
| InvoiceActions:77 | lambda$markPaid$2 | NullReturnVals | NO_COVERAGE | test: InvoiceActionsSurvivorsTest#T_12_21_anUnknownReceiptOrMemberIsNotFound |
| InvoiceActions:66 | lambda$memberLocale$1 | NegateConditionals (index 4, `!= null`) | SURVIVED | test: InvoiceActionsSurvivorsTest#T_12_20_theReceiptLanguageIsTheMembersOwnAndNoneWhenUnknownOrBlank |
| InvoiceActions:66 | lambda$memberLocale$1 | NegateConditionals (index 7, `!isBlank`) | SURVIVED | test: InvoiceActionsSurvivorsTest#T_12_20_theReceiptLanguageIsTheMembersOwnAndNoneWhenUnknownOrBlank |
| InvoiceActions:66 | lambda$memberLocale$1 | BooleanTrueReturnVals | SURVIVED | test: InvoiceActionsSurvivorsTest#T_12_20_theReceiptLanguageIsTheMembersOwnAndNoneWhenUnknownOrBlank |
| InvoiceActions:86 | markFailed | NegateConditionals (index 34, `sepa != null`) | SURVIVED | test: InvoiceActionsSurvivorsTest#T_12_30_aCollectingSepaReceiptIsReturnableAndACollectingCardOneIsNot (a COLLECTING CARD receipt with its STRIPE attempt: the mutant returns it) |
| InvoiceActions:86 | markFailed | NegateConditionals (index 39, `type == SEPA_DD`) | SURVIVED | test: InvoiceActionsSurvivorsTest#T_12_30_aCollectingSepaReceiptIsReturnableAndACollectingCardOneIsNot (same CARD receipt: `type != SEPA_DD` makes the mutant return it) |
| InvoiceActions:78 | markPaid | VoidMethodCall (forEach payable) | SURVIVED | test: InvoiceActionsSurvivorsTest#T_12_14_aBulkMarkPaidChecksEveryReceiptBeforePayingAny |
| InvoiceActions:66 | memberLocale | EmptyObjectReturnVals | SURVIVED | test: InvoiceActionsSurvivorsTest#T_12_20_theReceiptLanguageIsTheMembersOwnAndNoneWhenUnknownOrBlank |
| ManualUpfrontPayments:24 | lambda$list$0 | NegateConditionals (index 4, `status == null`) | SURVIVED | test: ManualUpfrontPaymentsSurvivorsTest#T_12_07_theListFiltersByStatusNewestFirstAndShowsNoRefundsAsAnEmptyList |
| ManualUpfrontPayments:24 | lambda$list$0 | NegateConditionals (index 9, `equals(status)`) | SURVIVED | test: ManualUpfrontPaymentsSurvivorsTest#T_12_07_theListFiltersByStatusNewestFirstAndShowsNoRefundsAsAnEmptyList |
| ManualUpfrontPayments:24 | lambda$list$0 | BooleanTrueReturnVals | SURVIVED | test: ManualUpfrontPaymentsSurvivorsTest#T_12_07_theListFiltersByStatusNewestFirstAndShowsNoRefundsAsAnEmptyList |
| ManualUpfrontPayments:29 | lambda$record$1 | NullReturnVals | NO_COVERAGE | test: ManualUpfrontPaymentsSurvivorsTest#T_12_21_anUnknownMemberIsNotFound |
| ManualUpfrontPayments:24 | list | EmptyObjectReturnVals | SURVIVED | test: ManualUpfrontPaymentsSurvivorsTest#T_12_07_theListFiltersByStatusNewestFirstAndShowsNoRefundsAsAnEmptyList |
| ManualUpfrontPayments:31 | record | ConditionalsBoundary (index 31, `due < 0`) | SURVIVED | test: ManualUpfrontPaymentsSurvivorsTest#T_12_07_zeroAmountsAreValidAndOnlyANegativeOneIsRefused |
| ManualUpfrontPayments:31 | record | ConditionalsBoundary (index 36, `paid < 0`) | SURVIVED | test: ManualUpfrontPaymentsSurvivorsTest#T_12_07_zeroAmountsAreValidAndOnlyANegativeOneIsRefused |
| ManualUpfrontPayments:33 | record | NegateConditionals (`"PACK".equals`) | SURVIVED | test: ManualUpfrontPaymentsSurvivorsTest#T_12_07_aManualPaymentIsPublishedAndShownWithItsManualProvider |
| ManualUpfrontPayments:43 | record | VoidMethodCall (BillingEvents::publish) | SURVIVED | test: ManualUpfrontPaymentsSurvivorsTest#T_12_07_aManualPaymentIsPublishedAndShownWithItsManualProvider |
| MemberPlanChangeService:37 | change | MathMutator (`version + 1`) | SURVIVED | test: MemberPlanChangeServiceSurvivorsTest#E11_T06_packToMonthlyChargesTheDiscountedEntryAndMarksTheLatestPackAsUsed |
| MemberPlanChangeService:26 | change | NegateConditionals (index 24, month equals today's) | NO_COVERAGE | test: MemberPlanChangeServiceSurvivorsTest#E11_T06_theCurrentMonthIsAValidEffectiveMonth |
| MemberPlanChangeService:34 | change | NegateConditionals (index 151/154) | SURVIVED | test: MemberPlanChangeServiceSurvivorsTest#E11_T06_packToMonthlyChargesTheDiscountedEntryAndMarksTheLatestPackAsUsed (the `"PACK_TO_MEMBER".equals` one also fails #E11_T06_aPackWhoseDiscountWasUsedPaysTheFullEntryAndIsNotMarkedAgain) |
| FakePaymentProvider:89 | lambda$forgetCustomer$8 | NullReturnVals | SURVIVED | test: FakePaymentProviderSurvivorsTest#T_12_15_forgettingACustomerTwiceCallsTheProviderOnce |
| FakePaymentProvider:81 | parseWebhook | NullReturnVals | NO_COVERAGE | test: FakePaymentProviderSurvivorsTest#T_12_15_aSignedWebhookIsParsedIntoItsEvent |
| FakePaymentProvider:67 | refund | NullReturnVals | NO_COVERAGE | test: FakePaymentProviderSurvivorsTest#T_12_17_aRefundWithoutOperationAnswersAndRecordsTheRefund |
| FakeWebhookCommand:39 | run | VoidMethodCall (println) | SURVIVED | test: FakeWebhookCommandSurvivorsTest#T_12_15_theCommandReportsTheDeliveredWebhook |
| InvoiceLists:32 | keys | EmptyObjectReturnVals | SURVIVED | test: InvoiceListsSurvivorsTest#E11_T06_theProviderServesTheInvoicesKey |
| InvoiceLists:54 | lambda$dataset$0 | EmptyObjectReturnVals | NO_COVERAGE | test: InvoiceListsSurvivorsTest#E11_T06_aFilterValueIsLabelledAsStored |
| InvoicingService:78 | context | NegateConditionals (`taxIncluded`) | SURVIVED | test: InvoicingServiceSurvivorsTest#T_12_05_pricesIncludeTaxUnlessTheClubTurnsItOff |
| InvoicingService:125 | lambda$order$4 | NegateConditionals (index 14, `b == null`) | SURVIVED | test: InvoicingServiceSurvivorsTest#T_12_04_namesCompareWithoutCaseAndAMissingOneIsBlank |
| InvoicingService:125 | lambda$order$4 | PrimitiveReturns (0) | SURVIVED | test: InvoicingServiceSurvivorsTest#T_12_04_namesCompareWithoutCaseAndAMissingOneIsBlank |
| InvoicingService:127 | lambda$order$5 | NegateConditionals (`memberNumber == null`) | SURVIVED | test: InvoicingServiceSurvivorsTest#T_12_04_equalNamesGoByMemberNumberAndAMissingNumberLast |
| InvoicingService:127 | lambda$order$5 | EmptyObjectReturnVals (Integer 0) | SURVIVED | test: InvoicingServiceSurvivorsTest#T_12_04_equalNamesGoByMemberNumberAndAMissingNumberLast |
| InvoicingService:103 | lambda$plan$3 | VoidMethodCall (List::forEach) | SURVIVED | test: InvoicingServiceSurvivorsTest#T_12_04_theMonthListsItsMembersInTheNumberingOrderWithTheirPendingCharges |
| InvoicingService:88 | linesFor | NullReturnVals | NO_COVERAGE | test: InvoicingServiceSurvivorsTest#T_12_01_anInactiveMembersMonthIsItsInactivityFee |
| InvoicingService:124 | order | VoidMethodCall (Collator::setStrength) | SURVIVED | test: InvoicingServiceSurvivorsTest#T_12_04_namesCompareWithoutCaseAndAMissingOneIsBlank |
| InvoicingService:95 | plan | VoidMethodCall (ArrayList::sort) | SURVIVED | test: InvoicingServiceSurvivorsTest#T_12_04_theMonthListsItsMembersInTheNumberingOrderWithTheirPendingCharges |
| InvoicingService:103 | plan | VoidMethodCall (Collection::forEach) | SURVIVED | test: InvoicingServiceSurvivorsTest#T_12_04_theMonthListsItsMembersInTheNumberingOrderWithTheirPendingCharges |
| InvoicingService$1:152 | inactivityFee | EmptyObjectReturnVals | SURVIVED | test: InvoicingServiceSurvivorsTest#T_12_01_anInactiveMembersMonthIsItsInactivityFee |
| MemberPlanChangeService:34 | change | NegateConditionals (index 151/154) | SURVIVED | test: MemberPlanChangeServiceSurvivorsTest#E11_T06_packToMonthlyChargesTheDiscountedEntryAndMarksTheLatestPackAsUsed (the `"PACK_TO_MEMBER".equals` one also fails #E11_T06_aPackWhoseDiscountWasUsedPaysTheFullEntryAndIsNotMarkedAgain) |
| MemberPlanChangeService:35 | change | NegateConditionals (`sourceIds == null`) | SURVIVED | test: MemberPlanChangeServiceSurvivorsTest#E11_T06_packToMonthlyChargesTheDiscountedEntryAndMarksTheLatestPackAsUsed |
| MemberPlanChangeService:24 | change | VoidMethodCall (validateChange) | SURVIVED | test: MemberPlanChangeServiceSurvivorsTest#E11_T06_aPlanOrPriceTheCatalogRefusesChangesNothing |
| MemberPlanChangeService:29 | lambda$change$0 | NegateConditionals (`sourceIds == null`) | SURVIVED | test: MemberPlanChangeServiceSurvivorsTest#E11_T06_aPackWhoseDiscountWasUsedPaysTheFullEntryAndIsNotMarkedAgain |
| MemberPlanChangeService:29 | lambda$change$0 | BooleanFalseReturnVals | SURVIVED | test: MemberPlanChangeServiceSurvivorsTest#E11_T06_aPackWhoseDiscountWasUsedPaysTheFullEntryAndIsNotMarkedAgain |
| ManualUpfrontPayments:53 | view | NegateConditionals (`provider == null`) | SURVIVED | test: ManualUpfrontPaymentsSurvivorsTest#T_12_07_aManualPaymentIsPublishedAndShownWithItsManualProvider |
| ManualUpfrontPayments:54 | view | NegateConditionals (`refunds == null`) | SURVIVED | test: ManualUpfrontPaymentsSurvivorsTest#T_12_07_theListFiltersByStatusNewestFirstAndShowsNoRefundsAsAnEmptyList |
| FakeProviderCommand:41 | run | VoidMethodCall (println) | SURVIVED | test: FakeProviderCommandSurvivorsTest#T_12_16_theCommandIsNamedAndReportsTheDeliveredCompletion |
| InvoicingService$Context:45 | module | BooleanFalseReturnVals | NO_COVERAGE | test: InvoicingServiceSurvivorsTest#T_12_22_aContextHasAModuleOnlyWhenTheClubHasItOn |
| InvoicingService$Context:45 | module | BooleanTrueReturnVals | NO_COVERAGE | test: InvoicingServiceSurvivorsTest#T_12_22_aContextHasAModuleOnlyWhenTheClubHasItOn |
| InvoicingService:117 | waiting | NegateConditionals (`member == null`) | SURVIVED | test: InvoicingServiceSurvivorsTest#T_12_18_aWaitingReceiptShowsItsMembersCurrentNameElseTheFrozenOne |

Totals: 55 rows. 55 have a test and 0 have a reason. No real production bug was found.

Notes:
- `ManualUpfrontPayments:54` (`refunds == null`) cannot be seen through `record()`, which always stores `List.of()`. Only a row written
  before S12 with no `refunds` can show it, so the list test uses one.
- `MemberPlanChangeService` has no S12 or S13 test id. `LifecycleIT` uses `T_13_20` for it, but T-13-20 is the `PackExpired` consumer, so
  these tests use the `E11_T06_` prefix. `InvoiceLists` also uses `E11_T06_` because no S12 test id covers the provider itself.
- `InvoicingService:124` (`setStrength`): the test compares two pairs whose last names differ only in case, one pair per case
  direction. Under the default TERTIARY strength one of the two comparisons flips, whichever case the JDK collator puts first.
