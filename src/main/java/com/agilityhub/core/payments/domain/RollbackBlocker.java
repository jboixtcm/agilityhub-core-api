package com.agilityhub.core.payments.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * S12 R-12-14: why a run cannot be rolled back (`RUN_NOT_ROLLBACKABLE.details.reasons[]`, `BillingRun.rollbackBlockers[]`).
 * `COLLECTION_DATE_PASSED` (E147): the remittance is still `GENERATED` and the club-local day has reached its requested collection
 * date — the bank may already have debited it, so a rollback would re-issue the same numbers in a second remittance.
 */
@Schema(enumAsRef = true)
public enum RollbackBlocker { REMITTANCE_SUBMITTED, COLLECTION_SUBMITTED, INVOICE_PAID, MANUAL_INVOICE_AFTER, COLLECTION_DATE_PASSED }
