package com.agilityhub.core.payments.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** S12 §6 `GET /checkout-sessions/{id}`: the status the return screen polls (`COMPLETE` is published as `PAID`). */
@Schema(enumAsRef = true)
public enum CheckoutStatus { PENDING, PAID, EXPIRED }
