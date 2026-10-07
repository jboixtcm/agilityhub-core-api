package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.InactivityPort;
import com.agilityhub.core.clubs.census.application.InactivityPeriodService;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class CensusInactivityAdapter implements InactivityPort {
    private final org.springframework.beans.factory.ObjectProvider<InactivityPeriodService> periods;
    public CensusInactivityAdapter(org.springframework.beans.factory.ObjectProvider<InactivityPeriodService> periods) { this.periods = periods; }
    @Override public Optional<Period> covering(String memberId, LocalDate date) {
        return periods.getObject().covering(memberId, date).map(p -> new Period(java.time.YearMonth.parse(p.fromMonth()).atDay(1),
                p.toMonth() == null ? null : java.time.YearMonth.parse(p.toMonth()).atEndOfMonth()));
    }
}
