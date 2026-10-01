package com.agilityhub.core.payments.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** S12 §5 `Invoice.status`: `PAID` with `refundedTotal = total` reads «reemborsat» (no state of its own); `CANCELLED` is terminal. */
@Schema(enumAsRef = true)
public enum InvoiceStatus { PENDING, COLLECTING, PAID, FAILED, CANCELLED }
