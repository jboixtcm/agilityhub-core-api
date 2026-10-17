package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 session 10 PIT survivors of {@link DemoBookingSeeder} (E4-T05/E5-T06 demo bookings): DemoPlanningService runs the
 * demo steps by {@code order()}, and the booking rows need the classes the S06 planning step (order 10) creates and validates,
 * so the step runs after it and before the S07 activities (30) and the E5-T06 scenario (35) that books on top of these rows.
 */
class DemoBookingSeederSurvivors3Test {
    final DemoBookingSeeder seeder = new DemoBookingSeeder(mock(ClassSessionService.class), mock(PlanningCatalogAccess.class),
            new ObjectMapper().findAndRegisterModules(), mock(BookingContext.class), mock(ClassSessionBookingAccess.class), mock(DemoMembers.class));

    @Test void E11_T06_theBookingStepRunsAfterThePlanningStepThatCreatesItsClasses() {
        assertThat(seeder.order()).isEqualTo(20);
    }
}
