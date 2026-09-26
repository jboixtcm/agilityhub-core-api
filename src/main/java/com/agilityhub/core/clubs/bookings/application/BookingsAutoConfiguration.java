package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.*;
import com.agilityhub.core.clubs.bookings.persistence.*;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.clubs.dashboard.application.ports.BookingActivity;
import com.agilityhub.core.clubs.dashboard.application.ports.DashboardPortDefaults;
import com.agilityhub.core.clubs.scheduling.application.ports.ClassBookingsPort;
import com.agilityhub.core.clubs.scheduling.application.ports.SchedulingPortDefaults;
import com.agilityhub.core.payments.application.PaymentProvider;
import com.agilityhub.core.payments.application.CheckoutService;
import com.agilityhub.core.platform.application.CensusClubSettings;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * S08 adapters and port defaults. Ordered before the S06 and S14 null objects, so the real `ClassBookingsPort` and
 * `BookingActivity` replace them (E5-T02 retires E4-T05's demo adapter); every bean backs off when an application or
 * test bean is registered. The E8 ports start as null objects; local/test register the in-memory ones; E6-T02 serves
 * `AttendanceStatePort` with {@link AttendanceStates}. The
 * waiting-list consolidation port is served by {@link WaitlistTransitions} (E5-T03, same context).
 */
@AutoConfiguration(before = {SchedulingPortDefaults.class, DashboardPortDefaults.class})
public class BookingsAutoConfiguration {
    @Bean @ConditionalOnMissingBean(ClassBookingsPort.class)
    ClassBookingsPort classBookings(BookingRepository bookings, WaitlistEntryRepository waitlist, BookingCancellationService cancellations) {
        return new ClassBookingsAdapter(bookings, waitlist, cancellations);
    }
    @Bean @ConditionalOnMissingBean(BookingActivity.class)
    BookingActivity bookingActivity(BookingRepository bookings, BookingMemberAccess census) { return new BookingActivityAdapter(bookings, census); }
    @Bean @ConditionalOnMissingBean(InactivityPort.class)
    InactivityPort noInactivity() { return (memberId, date) -> Optional.empty(); }
    @Bean @ConditionalOnMissingBean(PackBalancePort.class)
    PackBalancePort noPacks() {
        return new PackBalancePort() {
            public Optional<Balance> balance(String memberId, String dogId) { return Optional.empty(); }
            public String consume(String memberId, String dogId, String bookingId) { return null; }
            public String refund(String memberId, String dogId, String bookingId, java.time.LocalDate today) { return null; }
        };
    }
    /**
     * S10 follow-up (E6-T03) starts empty: no tasks, notes or observations, and not {@link DogFollowupPort#available()},
     * so the sheet and the card leave `pendingTasksCount` and the three card blocks out, the TASKS-off shape (E6-T02).
     */
    @Bean @ConditionalOnMissingBean(DogFollowupPort.class)
    DogFollowupPort noFollowup() {
        return new DogFollowupPort() {
            @Override public boolean available() { return false; }
            public java.util.Map<String, Integer> pendingTasks(java.util.Collection<String> dogIds) { return java.util.Map.of(); }
            public Optional<Note> instructorNote(String dogId) { return Optional.empty(); }
            public Tasks tasks(String dogId) { return new Tasks(0, 0, null); }
            public Optional<Observations> observations(String dogId) { return Optional.empty(); }
        };
    }
    /** S09/S07 read contracts of S10 (E6-T02): the training and activity adapters replace these when their contexts are present. */
    @Bean @ConditionalOnMissingBean(TrainingHistoryQuery.class)
    TrainingHistoryQuery noTrainingHistory() { return (dogIds, from) -> java.util.List.of(); }
    @Bean @ConditionalOnMissingBean(TrainingStatsQuery.class)
    TrainingStatsQuery noTrainingStats() { return (dogId, from, to) -> 0; }
    @Bean @ConditionalOnMissingBean(ActivityHistoryQuery.class)
    ActivityHistoryQuery noActivityHistory() { return (memberId, from) -> java.util.List.of(); }
    @Bean @ConditionalOnMissingBean(SingleClassChargePort.class)
    SingleClassChargePort singleClassCharges(BookingMemberAccess census, CheckoutService checkouts, ObjectProvider<PaymentProvider> gateways,
            BookingContext context, CensusClubSettings clubs) {
        return new SingleClassCharges(census, checkouts, gateways, context, clubs);
    }
}
