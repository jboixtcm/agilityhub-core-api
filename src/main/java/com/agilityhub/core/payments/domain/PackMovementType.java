package com.agilityhub.core.payments.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** S12 §3 `PackBalance.movements[].type` (append-only). */
@Schema(enumAsRef = true)
public enum PackMovementType { OPEN, CONSUME, REFUND, ADJUST, EXPIRE }
