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
import java.util.function.Function;
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
        var session=mock(ClassSession.class);when(session.id()).thenReturn("session");when(session.state()).thenReturn(ClassState.ACTIVE);when(session.levelIds()).thenReturn(List.of("B"));
        when(sessions.forWeek("week")).thenReturn(List.of(session));
        var classBookings=mock(com.agilityhub.core.clubs.scheduling.application.ports.ClassBookingsPort.class);
        var recipients=mock(com.agilityhub.core.clubs.census.application.SchedulingRecipients.class);
        when(classBookings.bookedDogs("session")).thenReturn(List.of("b1","b2","b3","c1","c2"));
        for(String id:List.of("b1","b2","b3","c1","c2")) when(recipients.dog(id)).thenReturn(Optional.of(new com.agilityhub.core.clubs.census.application.SchedulingRecipients.Dog(id,id,id.startsWith("b")?"B":"C")));
        when(booking.dogsWithBooking(anyString(), any(), any())).thenReturn(Set.of("dog"));
        when(booking.activeBookingsByLevel(anyString(), any(), any())).thenReturn(Map.of("B", 3, "C", 2));
        when(repository.dogCounts(Set.of("dog"))).thenReturn(List.of(new DashboardData.DogCount("B", 4, 1)));
        var query = new CoverageQuery(context, mock(TemplateQuery.class), weeks, sessions, dogs, classBookings, recipients, clock);
        try (var tenant = TenantContext.open("club")) {
            var first = query.get(null, null, "week"); assertThat(first.activeDogWeeks()).isEqualTo(1); assertThat(first.levels().getFirst().booked()).isEqualTo(3); assertThat(first.levels().getLast().booked()).isEqualTo(2);
            query.get(null, null, "week");
        }
        var until = Instant.parse("2026-11-01T23:00:00Z");
        verify(booking).dogsWithBooking("club", Instant.parse("2026-10-25T23:00:00Z"), until);
        verify(booking).dogsWithBooking("club", Instant.parse("2026-10-18T22:00:00Z"), until);
    }
    @Test void T_06_05_E29_coverageListsOnlyTheActiveProgressionLevels() {
        var repository = mock(com.agilityhub.core.clubs.dashboard.persistence.DashboardRepository.class);
        var booking = mock(BookingActivity.class); var dogs = new DogActivityQuery(repository, booking);
        var context = mock(PlanningContext.class); var weeks = mock(WeekGenerationUseCase.class);
        var sessions = mock(ClassSessionRepository.class); var clock = mock(ClubClock.class);
        var config = mock(ClubConfig.class); var club = mock(ClubConfig.ClubView.class);
        when(context.config()).thenReturn(config); when(config.club()).thenReturn(club); when(club.timeZone()).thenReturn("Europe/Madrid");
        when(config.get("levels.enabled", Boolean.class)).thenReturn(true);
        when(config.get("coverage.activeDogWeeks", Integer.class)).thenReturn(1);
        when(config.get("coverage.thresholds", Map.class)).thenReturn(Map.of("ok", 240, "tight", 190, "short", 150));
        Function<String, LocalizedText> name = code -> new LocalizedText(Map.of("ca", code), "ca");
        // S05 §12 order: CAD, …, G, then Teràpia and Pendent outside the progression; an inactive progression level is also left out.
        when(context.catalog()).thenReturn(new SchedulingCatalog(List.of(new SchedulingCatalog.Level("PENDENT", name.apply("Pendent"), 90, 5, true, false),
                new SchedulingCatalog.Level("TER", name.apply("Teràpia"), 80, 1, true, false), new SchedulingCatalog.Level("G", name.apply("G"), 70, 4, true, true),
                new SchedulingCatalog.Level("F", name.apply("F"), 60, 4, false, true), new SchedulingCatalog.Level("CAD", name.apply("Cadells"), 0, 5, true, true)), List.of(), List.of()));
        when(clock.today("club")).thenReturn(LocalDate.of(2026, 10, 28));
        var templates = mock(TemplateQuery.class); var template = mock(PlanningViews.WeekTemplate.class);
        when(templates.get("weekdays")).thenReturn(template); when(template.kind()).thenReturn(TemplateKind.WEEKDAYS);
        var therapy = mock(PlanningViews.TemplateClass.class); when(therapy.capacity()).thenReturn(1); when(therapy.levelIds()).thenReturn(List.of("TER"));
        var mixed = mock(PlanningViews.TemplateClass.class); when(mixed.capacity()).thenReturn(4); when(mixed.levelIds()).thenReturn(List.of("G", "TER"));
        when(template.classes()).thenReturn(List.of(therapy, mixed));
        when(booking.dogsWithBooking(anyString(), any(), any())).thenReturn(Set.of());
        when(repository.dogCounts(Set.of())).thenReturn(List.of(new DashboardData.DogCount("TER", 3, 0), new DashboardData.DogCount("PENDENT", 6, 0), new DashboardData.DogCount("G", 2, 0)));
        var query = new CoverageQuery(context, templates, weeks, sessions, dogs, mock(com.agilityhub.core.clubs.scheduling.application.ports.ClassBookingsPort.class),
                mock(com.agilityhub.core.clubs.census.application.SchedulingRecipients.class), clock);
        try (var tenant = TenantContext.open("club")) {
            var coverage = query.get("weekdays", null, null);
            assertThat(coverage.levels()).extracting(PlanningViews.CoverageLevel::levelId).containsExactly("CAD", "G");
            assertThat(coverage.levels().getLast().maxSeats()).isEqualTo(4);
            assertThat(coverage.levels().getLast().dogsTotal()).isEqualTo(2);
        }
    }
}
