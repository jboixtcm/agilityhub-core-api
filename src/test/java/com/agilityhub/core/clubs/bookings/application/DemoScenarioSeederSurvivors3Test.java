package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntryRepository;
import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.census.application.MemberService;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 session 10 PIT survivors of {@link DemoScenarioSeeder} (E5-T06 scenario): DemoPlanningService runs the demo steps
 * by {@code order()}; the scenario books, cancels and offers seats on the classes of the planning step (10) after the demo
 * bookings (20) and activities (30), and before the census cast step (36) and the attendance step (37).
 */
class DemoScenarioSeederSurvivors3Test {
    final DemoScenarioSeeder seeder = new DemoScenarioSeeder(mock(ClassSessionService.class), mock(ClassSessionBookingAccess.class),
            mock(PlanningCatalogAccess.class), mock(BookingContext.class), mock(DemoMembers.class), mock(WaitlistService.class),
            mock(BookingRepository.class), mock(WaitlistEntryRepository.class), mock(MemberService.class), new ObjectMapper().findAndRegisterModules());

    @Test void E11_T06_theScenarioStepRunsAfterTheDemoBookingsAndActivities() {
        assertThat(seeder.order()).isEqualTo(35);
    }
}
