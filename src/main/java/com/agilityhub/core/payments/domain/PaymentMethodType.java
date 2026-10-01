package com.agilityhub.core.payments.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** The frozen `Invoice.paymentMethod.type` and the D6 «Mètode» column (S12 §3; S03 `Member.paymentMethod.type`). */
@Schema(enumAsRef = true)
public enum PaymentMethodType { SEPA_DD, CARD, MANUAL }
