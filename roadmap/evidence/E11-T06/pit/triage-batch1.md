# E11-T06 · PIT batch 1 survivor triage (`payments.domain`, `payments.application.sepa`, `payments.application.stripe`)

Source: `batch1-not-killed.tsv` (23 rows). Staged tests: `.local/e11-t06-staged-tests/src/test/java/com/agilityhub/core/payments/...`. Totals: 16 rows get a test, 7 rows get a reason.

| Class:line | Method | Mutator | Status | Decision |
|---|---|---|---|---|
| PendingChargeRules:18 | onAttendance | NegateConditionals (`previousState == null`) | SURVIVED | test: PendingChargeRulesSurvivorsTest#T_12_08_aFirstMarkWithoutAPreviousStateChargesAndAChargedToChargedChangeDoesNot |
| SepaDirectDebits:68 | creditorIdentifier | ConditionalsBoundary (`id.length() < 8`) | SURVIVED | test: SepaDirectDebitsSurvivorsTest#T_12_06_anEightCharacterCreditorIdentifierStillTakesTheSuffix |
| StripePaymentProvider:62 | createCheckoutSession | NegateConditionals (`customerEmail() != null`) | SURVIVED | test: StripePaymentProviderSurvivorsTest#T_12_16_aPaymentCheckoutCarriesTheCustomerEmail |
| StripePaymentProvider:70 | createCheckoutSession | NegateConditionals (`setupFutureUsage() != null`) | SURVIVED | test: StripePaymentProviderSurvivorsTest#T_12_16_setupFutureUsageCreatesACustomerAndSavesTheCardOffSession (+ `#T_12_16_withoutSetupFutureUsageNoCustomerIsCreatedAndNoCardSaved`) |
| StripePaymentProvider:143 | lambda$expire$9 | NullReturnVals | SURVIVED | reason: equivalent — `expire` is `void` and discards what `calls.call(...)` returns; `StripeCalls.call` returns the lambda's value without reading it, so a null `Session` changes nothing. |
| StripePaymentProvider:131 | lambda$forgetCustomer$8 | NullReturnVals | SURVIVED | reason: equivalent — `forgetCustomer` is `void` and discards the deleted `Customer` (the 404 branch already returns null); `StripeCalls.call` never inspects the value. |
| InvoicingRules:253 | charges | EmptyObjectReturnVals (`return null` → `emptyList`) | NO_COVERAGE | test: InvoicingRulesSurvivorsTest#T_12_05_aSingleClassChargeInAnotherCurrencySkipsTheMember (also `#T_12_05_aFamilyMembersChargeInAnotherCurrencySkipsTheHolder`) |
| InvoicingRules:236 | familyShare | NegateConditionals (`period.isAfter(lastBilled)`) | NO_COVERAGE | test: InvoicingRulesSurvivorsTest#T_12_02_aLeavingFamilyMemberIsBilledThroughItsLastMonthOnly |
| InvoicingRules:240 | familyShare | NullReturnVals | NO_COVERAGE | test: InvoicingRulesSurvivorsTest#T_12_05_aFamilyMembersInactivityFeeInAnotherCurrencySkipsTheHolder |
| InvoicingRules:245 | familyShare | NullReturnVals | NO_COVERAGE | test: InvoicingRulesSurvivorsTest#T_12_05_aFamilyMembersChargeInAnotherCurrencySkipsTheHolder |
| InvoicingRules:137 | lambda$month$2 | BooleanTrueReturnVals | SURVIVED | test: InvoicingRulesSurvivorsTest#T_12_02_aHolderNotDueStaysOutWhenItsFamilyHasNoCharges |
| InvoicingRules:205 | own | ConditionalsBoundary (`cashPeriodMonths() > 1` → `>= 1`) | SURVIVED | reason: equivalent — differs only at `cashPeriodMonths = 1`, where the "semester" branch gives `cashMonths(M, next, 1) = [M]` (a due member's `next` is never after `M`) and `invoiceDay(periodEnd(M,1)+1) = invoiceDay(M+1)`: same months, same date. |
| InvoicingRules:224 | own | NullReturnVals | NO_COVERAGE | test: InvoicingRulesSurvivorsTest#T_12_05_aSingleClassChargeInAnotherCurrencySkipsTheMember |
| Pain008Document:120 | validate | VoidMethodCall (`Validator.setProperty(ACCESS_EXTERNAL_DTD, "")`) | SURVIVED | reason: defence-in-depth hardening — the JDK validator inherits the `SchemaFactory`'s secure processing and `ACCESS_EXTERNAL_DTD = ""` (lines 58-59), and `write` never emits a DOCTYPE, so removing the call is not observable in-process. |
| Pain008Document:121 | validate | VoidMethodCall (`Validator.setProperty(ACCESS_EXTERNAL_SCHEMA, "")`) | SURVIVED | reason: defence-in-depth hardening — inherited from the factory (line 60), and a `Schema` built by `newSchema(URL)` validates with its fixed grammar pool only (no `xsi:schemaLocation` fetch), so not observable in-process. |
| Pain008Document:98 | write | VoidMethodCall (`Marshaller.setProperty(JAXB_ENCODING, "UTF-8")`) | SURVIVED | reason: equivalent — the JAXB `Marshaller` default encoding is UTF-8, and with `JAXB_FRAGMENT` no declaration is written (the fixed `DECLARATION` is prepended), so the bytes are identical. |
| SepaText:37 | allowed | ConditionalsBoundary (`c <= 'Z'`) | SURVIVED | test: SepaTextSurvivorsTest#T_12_06_theUpperEndsOfTheLetterAndDigitRangesAreKept |
| SepaText:37 | allowed | ConditionalsBoundary (`c <= 'z'`) | SURVIVED | test: SepaTextSurvivorsTest#T_12_06_theUpperEndsOfTheLetterAndDigitRangesAreKept |
| SepaText:37 | allowed | ConditionalsBoundary (`c <= '9'`) | SURVIVED | test: SepaTextSurvivorsTest#T_12_06_theUpperEndsOfTheLetterAndDigitRangesAreKept |
| SepaText:22 | of | ConditionalsBoundary (`clean.length() <= max` → `<`) | SURVIVED | reason: equivalent — at `length == max` the mutant returns `clean.substring(0, max).stripTrailing()` = `clean.stripTrailing()` = `clean`, because `transliterate` already ends with `strip()`. |
| StripePaymentProvider:113 | parseWebhook | NullReturnVals | NO_COVERAGE | test: StripePaymentProviderSurvivorsTest#T_12_15_aSignedWebhookIsParsedIntoItsEvent |
| StripePaymentProvider:102 | refund | NullReturnVals | SURVIVED | test: StripePaymentProviderSurvivorsTest#T_12_17_theRefundWithoutAnOperationReturnsTheProvidersRefund |
| SignupPaymentEvent:10 | aggregateType | EmptyObjectReturnVals (`""`) | SURVIVED | test: SignupPaymentEventSurvivorsTest#E11_T06_upfrontPaymentEventsAreAboutTheUpfrontPaymentAggregate |

Mutant positions were identified from the PIT XML `<index>` of the killed siblings on the same line (e.g. `SepaText:37` survivors are indexes 8/16/24 = the three `<=` upper bounds; `PendingChargeRules:18` index 46 = `previousState == null`; `StripePaymentProvider:62` index 78 = `customerEmail() != null`; `InvoicingRules:236` index 43 = `period.isAfter(lastBilled)`).

No production bug found.
