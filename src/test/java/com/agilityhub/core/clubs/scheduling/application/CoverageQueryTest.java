package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.dashboard.application.*;
import com.agilityhub.core.clubs.dashboard.application.ports.BookingActivity;
import com.agilityhub.core.clubs.scheduling.domain.*;
import com.agilityhub.core.clubs.scheduling.persistence.*;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoverageQueryTest {
    @Test void T_06_05_T_06_18_activityWindowUsesCurrentWeekAndConfiguredHistoryAndWeekBookings() {
        var repository = mock(com.agilityhub.core.clubs.dashboard.persistence.DashboardRepository.class);
        var booking = mock(BookingActivity.class); var dogs = new DogActivityQuery(repository, booking);
        var context = mock(PlanningContext.class); var weeks = mock(WeekGenerationUseCase.class);
        var sessions = mock(ClassSessionRepository.class); var clock = mock(ClubClock.class);
        var config = mock(ClubConfig.class); var club = mock(ClubConfig.ClubView.class);
        when(context.config()).thenReturn(config); when(config.club()).thenReturn(club); when(club.timeZone()).thenReturn("Europe/Madrid");
        when(config.get("levels.enabled", Boolean.class)).thenReturn(true);
        when(config.get("coverage.activeDogWeeks", Integer.class)).thenReturn(1, 2);
        when(config.get("coverage.thresholds", Map.class)).thenReturn(Map.of("ok", 240, "tight", 190, "short", 150));
        when(context.catalog()).thenReturn(new SchedulingCatalog(List.of(new SchedulingCatalog.Level("B", new LocalizedText(Map.of("ca", "B"), "ca"), 0, 5, true), new SchedulingCatalog.Level("C", new LocalizedText(Map.of("ca", "C"), "ca"), 1, 5, true)), List.of(), List.of()));
        LocalDate today = LocalDate.of(2026, 10, 28); when(clock.today("club")).thenReturn(today);
        when(weeks.require("week")).thenReturn(new Week("week", "club", 2026, 44, LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 1), WeekState.GENERATED, null, null, null, null, null, null, 1L, null, null, null, null));
        when(sessions.forWeek("week")).thenReturn(List.of());
        when(booking.dogsWithBooking(anyString(), any(), any())).thenReturn(Set.of("dog"));
        when(booking.activeBookingsByLevel(anyString(), any(), any())).thenReturn(Map.of("B", 3, "C", 2));
        when(repository.dogCounts(Set.of("dog"))).thenReturn(List.of(new DashboardData.DogCount("B", 4, 1)));
        var query = new CoverageQuery(context, mock(TemplateQuery.class), weeks, sessions, dogs, booking, clock);
        try (var tenant = TenantContext.open("club")) {
            var first = query.get(null, null, "week"); assertThat(first.activeDogWeeks()).isEqualTo(1); assertThat(first.levels().getFirst().booked()).isEqualTo(3); assertThat(first.levels().getLast().booked()).isEqualTo(2);
            query.get(null, null, "week");
        }
        var until = Instant.parse("2026-11-01T23:00:00Z");
        verify(booking).dogsWithBooking("club", Instant.parse("2026-10-25T23:00:00Z"), until);
        verify(booking).dogsWithBooking("club", Instant.parse("2026-10-18T22:00:00Z"), until);
    }
}
