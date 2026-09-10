package com.agilityhub.core.clubs.dashboard.application;

import com.agilityhub.core.clubs.dashboard.application.DashboardData.*;
import com.agilityhub.core.clubs.dashboard.application.ports.BookingActivity;
import com.agilityhub.core.clubs.dashboard.domain.DashboardPeriod;
import com.agilityhub.core.clubs.dashboard.persistence.DashboardRepository;
import com.agilityhub.core.shared.application.*;
import java.time.*;
import java.util.List;
import org.springframework.stereotype.Service;

/** Shared S14/S06 computation: active dogs of active members, grouped by level. */
@Service
public class DogActivityQuery {
    private final DashboardRepository repository;
    private final BookingActivity bookings;
    public DogActivityQuery(DashboardRepository repository, BookingActivity bookings) { this.repository = repository; this.bookings = bookings; }
    public List<DogCount> counts(LocalDate today, ZoneId zone, int activeDogWeeks) {
        var period = new DashboardPeriod(today, zone);
        return repository.dogCounts(bookings.dogsWithBooking(TenantContext.require(), period.activityFrom(activeDogWeeks), period.until()));
    }
}
