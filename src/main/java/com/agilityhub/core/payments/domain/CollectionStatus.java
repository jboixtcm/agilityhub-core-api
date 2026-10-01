package com.agilityhub.core.payments.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** S12 §5 `Collection.status` (append-only attempts). */
@Schema(enumAsRef = true)
public enum CollectionStatus { CREATED, SUBMITTED, SUCCEEDED, FAILED, REFUNDED }
