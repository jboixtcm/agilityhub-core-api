package com.agilityhub.core.clubs.scheduling.application.ports;

import java.util.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class SchedulingPortDefaults {
    @Bean @ConditionalOnMissingBean(ClassBookingsPort.class)
    ClassBookingsPort classBookings() { return new ClassBookingsPort() {
        public List<BookingRef> activeBookings(String id) { return List.of(); }
        public List<WaitlistRef> liveWaitlist(String id) { return List.of(); }
        public List<WaitlistRef> waitlistEntries(List<String> ids) { return List.of(); }
        public CancellationEffects cancelAllByClub(String id, String reason, String actor) { return new CancellationEffects(List.of(), List.of()); }
    }; }
    @Bean @ConditionalOnMissingBean(TrainingConflictPort.class)
    TrainingConflictPort trainingConflicts() { return new TrainingConflictPort() {
        public List<Booking> findActiveBookings(String ring, java.time.Instant from, java.time.Instant to) { return List.of(); }
        public void cancelByClub(List<String> ids, String reason) { }
    }; }
    @Bean @ConditionalOnMissingBean(TrainingOccupancyPort.class)
    TrainingOccupancyPort trainingOccupancy() { return (from, to, rings, role) -> List.of(); }
    @Bean @ConditionalOnMissingBean(ActivityTitlePort.class)
    ActivityTitlePort activityTitles() { return (ids, locale) -> Map.of(); }
}
