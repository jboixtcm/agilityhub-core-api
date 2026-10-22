package com.agilityhub.core.payments.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * S12 R-12-07 simulation incidents (+ `PROVIDER_DISABLED`, R-12-28 and S12 §13): the member is skipped by the run.
 * `NO_NEXT_INVOICE_DATE` (E11-T15, the audit of 21-10): an `ACTIVE` member on a `MONTHLY` plan without `nextInvoiceDate` — the
 * run never invents one; the admin sets it (`PUT /members/{id}/next-invoice-date`, E146).
 * `NO_PAYMENT_METHOD` (E11-T15 round 2, ruling E156): a family holder without a payment method — it keeps its group, whose
 * members wait with it, and its months are caught up when the method returns. `MANDATE_PENDING` (E156): a `SEPA_DD` member
 * without a usable mandate (no reference, one pain.008 cannot carry, or no signature) — skipped instead of refusing the run.
 */
@Schema(enumAsRef = true)
public enum BillingIncidentCode { NO_BANK_ACCOUNT, NO_PLAN, NO_PRICE, CARD_INVALID, CURRENCY_MISMATCH, PROVIDER_DISABLED, MEMBER_NOT_ACTIVE, PAYMENT_METHOD_CHANGED,
    NO_NEXT_INVOICE_DATE, NO_PAYMENT_METHOD, MANDATE_PENDING }
