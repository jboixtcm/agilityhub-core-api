package com.agilityhub.core.payments.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** S12 §3 `UpfrontPayment.concept` (S04's signup concepts stay in `signupConcept`). */
@Schema(enumAsRef = true)
public enum UpfrontConcept { ENTRY_FEE, FIRST_MONTH, PACK, SINGLE_CLASS, ACTIVITY, OTHER }
