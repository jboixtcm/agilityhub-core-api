package com.agilityhub.core.payments.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** S12 §3 `InvoiceLine.origin` (+ `MIGRATED`, the S18 import's lines, model Annex B). */
@Schema(enumAsRef = true)
public enum InvoiceLineOrigin { MONTHLY_FEE, MAINTENANCE_FEE, INACTIVITY_FEE, SINGLE_CLASS, PACK, ADJUSTMENT, MIGRATED }
