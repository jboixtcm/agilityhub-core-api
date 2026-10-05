package com.agilityhub.core.shared.application;

import java.time.YearMonth;
import java.util.Optional;

/** S13 billing contract implemented by census; shared to preserve the context dependency direction (E90). */
public interface LeaveBillingPort {
    Optional<YearMonth> lastInvoicedMonth(String memberId);
}
