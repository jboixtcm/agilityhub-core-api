package com.agilityhub.core.clubs.dashboard.application.ports;

import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import static org.assertj.core.api.Assertions.*;

class DashboardPortDefaultsTest {
    final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(DashboardPortDefaults.class));
    @Test void T_14_01_T_14_23_absentVerticalsReturnEmptyData() {
        runner.run(context -> {
            assertThat(context.getBean(ClassOccupancyQuery.class).sessions("club", Instant.EPOCH, Instant.EPOCH)).isEmpty();
            assertThat(context.getBean(TrainingBookingsQuery.class).bookings("club", Instant.EPOCH, Instant.EPOCH)).isEmpty();
            assertThat(context.getBean(BookingActivity.class).dogsWithBooking("club", Instant.EPOCH, Instant.EPOCH)).isEmpty();
            assertThat(context.getBean(PendingRequestsQuery.class).counts("club")).isEqualTo(new PendingRequestsQuery.Counts(0, 0));
            assertThat(context.getBean(FollowUpUnreadQuery.class).count("club", "account")).isZero();
            assertThat(context.getBean(ClassSessionsQuery.class).sessions("club", LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 12))).isEmpty();
            assertThat(context.getBean(RiskEvaluator.class).atRisk(null)).isFalse();
        });
    }
    @Configuration(proxyBeanMethods = false)
    static class Scheduling {
        @Bean ClassOccupancyQuery implementedOccupancy() { return (club, from, until) -> List.of(new ClassOccupancyQuery.Session(from, "ACTIVE", 5, 3, 0)); }
    }
    @Test void T_14_01_schedulingCanReplaceItsPortWithoutChangingDashboard() {
        runner.withUserConfiguration(Scheduling.class).run(context -> {
            assertThat(context).hasSingleBean(ClassOccupancyQuery.class);
            assertThat(context.getBean(ClassOccupancyQuery.class).sessions("club", Instant.EPOCH, Instant.EPOCH)).hasSize(1);
            assertThat(context).hasSingleBean(BookingActivity.class);
        });
    }
}
