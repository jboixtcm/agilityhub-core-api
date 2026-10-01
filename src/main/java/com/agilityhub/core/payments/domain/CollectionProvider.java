package com.agilityhub.core.payments.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** S12 §3 `Collection.provider` and the keys of a run's `byProvider` (ADR-009). */
@Schema(enumAsRef = true)
public enum CollectionProvider { SEPA_XML, STRIPE, MANUAL }
