package com.agilityhub.core.payments.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** S12 R-12-14: why a run cannot be rolled back (`RUN_NOT_ROLLBACKABLE.details.reasons[]`, `BillingRun.rollbackBlockers[]`). */
@Schema(enumAsRef = true)
public enum RollbackBlocker { REMITTANCE_SUBMITTED, COLLECTION_SUBMITTED, INVOICE_PAID, MANUAL_INVOICE_AFTER }
