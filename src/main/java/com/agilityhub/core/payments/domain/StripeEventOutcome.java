package com.agilityhub.core.payments.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** S12 §3 `StripeEvent.outcome` (R-12-21: an out-of-order event is `IGNORED`). */
@Schema(enumAsRef = true)
public enum StripeEventOutcome { PROCESSED, IGNORED, FAILED }
