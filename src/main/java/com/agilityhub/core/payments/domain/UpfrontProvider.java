package com.agilityhub.core.payments.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** S12 §3 `UpfrontPayment.provider.type`: Stripe Checkout or a payment the admin records by hand. */
@Schema(enumAsRef = true)
public enum UpfrontProvider { STRIPE, MANUAL }
