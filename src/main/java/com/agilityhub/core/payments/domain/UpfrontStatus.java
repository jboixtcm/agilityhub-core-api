package com.agilityhub.core.payments.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** S12 §3/§5 `UpfrontPayment.status`: S04 §5's diagram plus `PAID → REFUNDED` (R-12-20). */
@Schema(enumAsRef = true)
public enum UpfrontStatus { DUE, CHECKOUT_PENDING, PARTIAL, PAID, CANCELLED, REFUNDED }
