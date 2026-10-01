package com.agilityhub.core.payments.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** S12 §3 `Invoice.kind`: `PERIODIC` (a billing run), `MANUAL` (an adjustment, R-12-19), `MIGRATED` (S18 import, model Annex B). */
@Schema(enumAsRef = true)
public enum InvoiceKind { PERIODIC, MANUAL, MIGRATED }
