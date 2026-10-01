package com.agilityhub.core.payments.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** S12 R-12-07 simulation incidents (+ `PROVIDER_DISABLED`, R-12-28 and S12 §13): the member is skipped by the run. */
@Schema(enumAsRef = true)
public enum BillingIncidentCode { NO_BANK_ACCOUNT, NO_PLAN, NO_PRICE, CARD_INVALID, CURRENCY_MISMATCH, PROVIDER_DISABLED }
