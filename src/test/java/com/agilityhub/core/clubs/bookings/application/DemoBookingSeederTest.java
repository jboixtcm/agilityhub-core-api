package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionService;
import com.agilityhub.core.shared.application.DemoSeedStep;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** E5-T14 (review E5-T09 #3): a re-anchored run books only into the weeks it generated, never into a kept week. */
class DemoBookingSeederTest {
    static final LocalDate MONDAY = LocalDate.parse("2026-09-14");

    static Map<String, Object> row(int week) {
        return Map.of("week", week, "day", "MONDAY", "start", "08:30", "ring", "CEN", "booked", 1, "withPack", 0, "waiting", 0);
    }

    @Test void T_06_28_aReanchoredRunBooksOnlyIntoTheWeeksItGenerated() {
        var sessions = mock(ClassSessionService.class); var catalogs = mock(PlanningCatalogAccess.class);
        var classes = mock(ClassSessionBookingAccess.class); var members = mock(DemoMembers.class);
        when(catalogs.ringIdsByShortName()).thenReturn(Map.of("CEN", "ring-cen"));
        when(sessions.slot(any(), any(), any())).thenReturn(Optional.empty());
        var seeder = new DemoBookingSeeder(sessions, catalogs, new ObjectMapper().findAndRegisterModules(), null, classes, members, null);
        var specification = Map.<String, Object>of("bookings", List.of(row(0), row(1), row(2)));
        // Weeks 0 and 1 were kept (one of them possibly validated by the run); only week 2 was generated.
        var counts = seeder.apply(new DemoSeedStep.Input(specification, 42, MONDAY, "admin", Set.of(), List.of(), MONDAY, true, new TreeSet<>(Set.of(2))));
        assertThat(counts).containsEntry("keptClasses", 3).containsEntry("classBookings", 0).containsEntry("classWaitlist", 0);
        verify(sessions).slot(MONDAY.plusWeeks(2), "08:30", "ring-cen");
        verify(sessions, never()).slot(eq(MONDAY), any(), any());
        verify(sessions, never()).slot(eq(MONDAY.plusWeeks(1)), any(), any());
        verifyNoInteractions(classes);
        // The first run generates every week: nothing is filtered by week.
        var first = new DemoSeedStep.Input(specification, 42, MONDAY, "admin", Set.of(), List.of(), MONDAY, false);
        assertThat(first.dated(0)).isTrue(); assertThat(first.generatedWeeks()).isEmpty();
    }
}
