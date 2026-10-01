package com.agilityhub.core.payments.application.ports;

import java.time.YearMonth;
import java.util.Optional;

/**
 * S13 R-13-11 `LeaveBillingService` as S12's invoicing reads it (E8-T02): the last month a leaving member is billed; the run
 * of a later month leaves the member out (R-12-01). The default null object ({@link BillingPortDefaults}) answers empty —
 * nobody is leaving: **E8-T05** (`LeaveBillingService`) replaces it.
 */
public interface LeaveBillingPort {
    Optional<YearMonth> lastInvoicedMonth(String memberId);
}
