# Current survivor review checkpoint

These are completed mutation outcomes from the still-running integration attempt 100, not a complete package report or final triage.

| Class / method / line / instruction | Outcome | Review |
|---|---|---|
| `PaymentRefunds.absorb:193`, boundary, index 74 | SURVIVED | Equivalent for the valid nonnegative reservation ledger: at zero overshoot the mutant attempts `credit(current,current)`, which changes no Mongo value and returns false (`PendingChargeRepository.credit` uses modified count); `settledExcess = -reserved <= 0` then returns. No audit, event, warning or persisted value changes. Retain in denominator. |
| `PaymentRefunds.absorb:198`, boundary, index 129 | SURVIVED | Test gap at settled excess zero with a positive pending reservation and a billed credit. Stage `PaymentRefundCreditSurvivorsTest.T_12_17_aReservationBeyondABilledCreditDoesNotReportZeroAsOverpaid`: 1500 captured, 500 billed credit, 1000 settled refund, 100 pending; expect no intervention audit, event or warning. Not yet compiled or executed. |
| `PaymentRefunds.absorb:199`, negation, index 148 | SURVIVED | The existing integration assertion counts one warning after two refunds; the mutant can move the warning from the first refund to the second. Stage `PaymentRefundCreditSurvivorsTest.T_12_17_aBilledCreditWarnsOnItsFirstExcessAndNeverAgain`: assert the warning immediately when the first-intervention transition succeeds, and no warning when it was already recorded. Not yet compiled or executed. |

The staged source is `.local/e11-t06-staged/PaymentRefundCreditSurvivorsTest.java`. It adds a test class without changing the existing tests or production code. Install it only after attempt 100 finishes, then run its tests and a mutation/counterfactual regression before claiming these gaps closed. Its repository fixture makes the settled refund immediately visible to the subsequent ledger read, as Mongo does inside the transaction.
