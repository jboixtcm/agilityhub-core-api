package com.agilityhub.core.payments.application.ports;

import java.util.List;

/**
 * S12 R-12-13 `POST /billing/runs/{id}/card-charges` (E8-T02 declares it): the run's `CARD` invoices charged off-session. The
 * default null object ({@link BillingPortDefaults}) has no payment provider and answers `422 PAYMENT_PROVIDER_NOT_ENABLED`:
 * **E8-T04** (`StripePaymentProvider`) replaces it.
 */
public interface CardChargingPort {
    record Skip(String invoiceId, String reason) { }
    record Result(int submitted, List<Skip> skipped) { }
    Result chargeRun(String runId);
}
