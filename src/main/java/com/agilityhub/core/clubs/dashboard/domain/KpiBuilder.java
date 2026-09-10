package com.agilityhub.core.clubs.dashboard.domain;

import com.agilityhub.core.clubs.dashboard.application.DashboardData.*;
import com.agilityhub.core.clubs.dashboard.application.ports.*;
import java.math.*;
import java.util.*;

public final class KpiBuilder {
    private KpiBuilder() { }
    public static Occupancy occupancy(List<ClassOccupancyQuery.Session> sessions, DashboardPeriod period, boolean waitlist) {
        int capacity = 0, booked = 0, waiting = 0;
        for (var session : sessions) {
            if (!Set.of("ACTIVE", "FINISHED").contains(session.state()) || !period.inWeek(session.startsAt())) { continue; }
            capacity += session.capacity(); booked += session.booked(); waiting += session.waiting();
        }
        Double percent = capacity == 0 ? null : BigDecimal.valueOf(booked).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(capacity), 0, RoundingMode.HALF_UP).doubleValue();
        return new Occupancy(percent, booked, capacity, waitlist ? waiting : 0);
    }
    public static Training training(List<TrainingBookingsQuery.Booking> bookings, DashboardPeriod period) {
        var active = bookings.stream().filter(b -> "ACTIVE".equals(b.status()) && period.inWeek(b.startsAt())).toList();
        return new Training(active.size(), (int) active.stream().map(TrainingBookingsQuery.Booking::memberId).distinct().count());
    }
}
