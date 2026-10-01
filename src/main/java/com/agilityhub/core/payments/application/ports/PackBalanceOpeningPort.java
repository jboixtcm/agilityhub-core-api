package com.agilityhub.core.payments.application.ports;

import java.time.LocalDate;

/**
 * S12 R-12-23 `PackBalanceService.open` (E8-T02 declares it): an `UpfrontPayment` with `concept = PACK` that becomes `PAID`
 * opens the dog's pack, idempotently per payment. The default null object ({@link BillingPortDefaults}) does nothing:
 * **E8-T05** (`PackBalanceService`) replaces it.
 */
public interface PackBalanceOpeningPort {
    /** {@code paidOn} is the club-local day of the payment (`openedOn`). */
    void open(String memberId, String dogId, String upfrontPaymentId, LocalDate paidOn);
}
