package com.agilityhub.core.payments.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** S12 §5 `PackBalance.state`: `ACTIVE → EXPIRED` (S15 P5a) · `EXPIRED → ACTIVE` (adjustment with a new `expiresOn`) · `ACTIVE → CLOSED`. */
@Schema(enumAsRef = true)
public enum PackBalanceState { ACTIVE, EXPIRED, CLOSED }
