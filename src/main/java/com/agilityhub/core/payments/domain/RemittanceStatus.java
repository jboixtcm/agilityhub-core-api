package com.agilityhub.core.payments.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** S12 §5 `Remittance.status`: `GENERATED → SUBMITTED` (marked by hand) or `GENERATED → ROLLED_BACK`. */
@Schema(enumAsRef = true)
public enum RemittanceStatus { GENERATED, SUBMITTED, ROLLED_BACK }
