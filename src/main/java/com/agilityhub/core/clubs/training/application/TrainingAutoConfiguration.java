package com.agilityhub.core.clubs.training.application;

import com.agilityhub.core.clubs.catalogs.application.RingTrainingBookings;
import com.agilityhub.core.clubs.census.application.TrainingMemberAccess;
import com.agilityhub.core.clubs.dashboard.application.ports.DashboardPortDefaults;
import com.agilityhub.core.clubs.dashboard.application.ports.TrainingBookingsQuery;
import com.agilityhub.core.clubs.scheduling.application.RingScheduleAccess;
import com.agilityhub.core.clubs.scheduling.application.ports.SchedulingPortDefaults;
import com.agilityhub.core.clubs.scheduling.application.ports.TrainingConflictPort;
import com.agilityhub.core.clubs.scheduling.application.ports.TrainingOccupancyPort;
import com.agilityhub.core.clubs.training.application.ports.RingSetupPort;
import com.agilityhub.core.clubs.training.persistence.TrainingBookingRepository;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.Optional;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * S09 adapters. Ordered before the S06 and S14 null objects, so the real `TrainingOccupancyPort`,
 * `TrainingConflictPort` and `TrainingBookingsQuery` replace them (E5-T04); every bean backs off when an application
 * or test bean is registered. `RingSetupPort` stays a null object until S16 (courses) supplies the real one.
 */
@AutoConfiguration(before = {SchedulingPortDefaults.class, DashboardPortDefaults.class})
public class TrainingAutoConfiguration {
    @Bean @ConditionalOnMissingBean(TrainingOccupancyPort.class)
    TrainingOccupancyPort trainingOccupancy(TrainingContext context, TrainingBookingRepository bookings, RingScheduleAccess schedule, TrainingMemberAccess census) {
        return new TrainingOccupancyService(context, bookings, schedule, census);
    }
    @Bean @ConditionalOnMissingBean(TrainingConflictPort.class)
    TrainingConflictPort trainingConflicts(TrainingBookingRepository bookings, TrainingBookingService service, TrainingMemberAccess census, TrainingSlotLocks slotLocks) {
        return new TrainingConflictService(bookings, service, census, slotLocks);
    }
    /** S05 R-05-08 → R-09-13: a ring that stops being reservable (E5-T09). */
    @Bean @ConditionalOnMissingBean(RingTrainingBookings.class)
    RingTrainingBookings ringTrainingBookings(TrainingContext context, TrainingBookingRepository bookings, TrainingBookingService service, TrainingMemberAccess census,
            TrainingSlotLocks slotLocks) {
        return new RingReservability(context, bookings, service, census, slotLocks);
    }
    /** E3-T04 dashboard KPI: training bookings by session start, any state (the KPI keeps the ACTIVE ones). */
    @Bean @ConditionalOnMissingBean(TrainingBookingsQuery.class)
    TrainingBookingsQuery trainingBookingsQuery(TrainingBookingRepository bookings) {
        return (clubId, from, until) -> {
            if (!TenantContext.require().equals(clubId)) { throw new ApiException(ErrorCode.TENANT_MISMATCH); }
            return bookings.startingBetween(clubId, from, until).stream().map(b -> new TrainingBookingsQuery.Booking(b.memberId(), b.startsAt(), b.state().name())).toList();
        };
    }
    @Bean @ConditionalOnMissingBean(RingSetupPort.class)
    RingSetupPort noRingSetups() { return (setupId, locale) -> Optional.empty(); }
}
