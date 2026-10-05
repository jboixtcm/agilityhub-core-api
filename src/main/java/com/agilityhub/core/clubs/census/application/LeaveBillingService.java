package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.LeaveBillingPort;
import java.time.*;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** S13 R-13-11: invoices already issued remain unchanged. */
@Service
public class LeaveBillingService implements LeaveBillingPort {
    private final CensusAccess census;
    public LeaveBillingService(CensusAccess census) { this.census = census; }
    @Override public Optional<YearMonth> lastInvoicedMonth(String memberId) {
        if (!census.enabled(Module.BILLING)) { return Optional.empty(); }
        return lastMonth(census.members.require(memberId).leaveDate, census.config().get("leave.fullMonthIfLater", Boolean.class));
    }
    public static Optional<YearMonth> lastMonth(LocalDate date, boolean fullMonth) {
        if (date == null) { return Optional.empty(); }
        return Optional.of(YearMonth.from(date).minusMonths(fullMonth || date.getDayOfMonth() == date.lengthOfMonth() ? 0 : 1));
    }
}
