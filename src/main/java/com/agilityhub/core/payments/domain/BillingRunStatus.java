package com.agilityhub.core.payments.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** S12 §5 `BillingRun.status`: `GENERATED → CHARGING → COMPLETED`; `GENERATED/CHARGING → ROLLED_BACK` (R-12-14). */
@Schema(enumAsRef = true)
public enum BillingRunStatus { GENERATED, CHARGING, COMPLETED, ROLLED_BACK }
