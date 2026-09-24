package com.agilityhub.core.clubs.training.application;

import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.clubs.training.domain.TrainingForeignEvent;
import com.agilityhub.core.shared.application.*;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.stereotype.Service;

/**
 * S09 §7 consumed events (consumer `training`, one durable bean per type, idempotent: invalidating twice is harmless).
 * A class or a new ring block invalidates the grid cache of its days; weeks, cancelled/updated blocks, activities,
 * rings, modules and the S09 parameters (`training.*`, `club.openingHours`, `club.holidays`, `club.timeZone`,
 * `bookings.weekOpensAt`, `bookings.limitUnit`) the whole club. The dog/level events change no state (eligibility is
 * computed on read), and `WeekOpened` resets nothing: the counter is per session week and the summary is always
 * computed live. `TrainingCounterReset` (S15 P1, «S09 només invalida cache») drops the club's grid cache.
 */
@Service
public class TrainingConsumers {
    static final List<String> DAYS = List.of("ClassSessionCreated", "ClassSessionUpdated", "ClassCancelledByClub", "ClassAutoCancelled", "RingBlockCreated");
    static final List<String> CLUB = List.of("WeekGenerated", "WeekValidated", "RingBlockCancelled", "RingBlockUpdated", "ActivityPublished", "ActivityCancelled",
            "RingChanged", "ClubModulesChanged", "ParameterChanged", "TrainingCounterReset");
    static final List<String> STATELESS = List.of("DogFreeTrainingChanged", "DogLevelChanged", "LevelChanged", "WeekOpened");
    static final Set<String> PARAMETERS = Set.of("club.openingHours", "club.holidays", "club.timeZone", "bookings.weekOpensAt", "bookings.limitUnit");
    private final TrainingGridCache cache; private final ClassSessionBookingAccess classes; private final ClubClock clocks;
    public TrainingConsumers(TrainingGridCache cache, ClassSessionBookingAccess classes, ClubClock clocks) { this.cache = cache; this.classes = classes; this.clocks = clocks; }

    /** @param type the handler's catalog type (some envelopes, e.g. `ParameterChanged`, do not serialise their type) */
    public void handle(String type, TrainingForeignEvent event) {
        if (STATELESS.contains(type)) { return; }
        if (type.equals("ParameterChanged")) {
            String key = Objects.toString(event.payload().get("key"), "");
            if (key.startsWith("training.") || PARAMETERS.contains(key)) { cache.invalidateClub(event.clubId()); }
            return;
        }
        if (CLUB.contains(type)) { cache.invalidateClub(event.clubId()); return; }
        try (var tenant = TenantContext.open(event.clubId())) {
            var days = days(type, event);
            if (days.isEmpty()) { cache.invalidateClub(event.clubId()); } else { cache.invalidate(event.clubId(), days); }
        }
    }
    /** The local days an event touches; empty = unknown (the caller drops the whole club). */
    private Set<LocalDate> days(String type, TrainingForeignEvent event) {
        var result = new TreeSet<LocalDate>();
        if (type.startsWith("Class")) {
            String id = Objects.toString(event.payload().getOrDefault("classId", event.aggregateId()), null);
            classes.find(id).ifPresent(s -> result.add(s.date()));
            return result;
        }
        try {
            var zone = clocks.now(event.clubId()).getZone();
            var from = Instant.parse(event.payload().get("from").toString()).atZone(zone).toLocalDate();
            var to = Instant.parse(event.payload().get("to").toString()).atZone(zone).toLocalDate();
            for (var date = from; !date.isAfter(to); date = date.plusDays(1)) { result.add(date); }
        } catch (RuntimeException unreadable) { result.clear(); }
        return result;
    }

    @Configuration(proxyBeanMethods = false)
    static class Handlers {
        @Bean DomainEventHandler<TrainingForeignEvent> trainingClassSessionCreated(TrainingConsumers c) { return handler("ClassSessionCreated", c); }
        @Bean DomainEventHandler<TrainingForeignEvent> trainingClassSessionUpdated(TrainingConsumers c) { return handler("ClassSessionUpdated", c); }
        @Bean DomainEventHandler<TrainingForeignEvent> trainingClassCancelledByClub(TrainingConsumers c) { return handler("ClassCancelledByClub", c); }
        @Bean DomainEventHandler<TrainingForeignEvent> trainingClassAutoCancelled(TrainingConsumers c) { return handler("ClassAutoCancelled", c); }
        @Bean DomainEventHandler<TrainingForeignEvent> trainingWeekGenerated(TrainingConsumers c) { return handler("WeekGenerated", c); }
        @Bean DomainEventHandler<TrainingForeignEvent> trainingWeekValidated(TrainingConsumers c) { return handler("WeekValidated", c); }
        @Bean DomainEventHandler<TrainingForeignEvent> trainingRingBlockCreated(TrainingConsumers c) { return handler("RingBlockCreated", c); }
        @Bean DomainEventHandler<TrainingForeignEvent> trainingRingBlockCancelled(TrainingConsumers c) { return handler("RingBlockCancelled", c); }
        @Bean DomainEventHandler<TrainingForeignEvent> trainingRingBlockUpdated(TrainingConsumers c) { return handler("RingBlockUpdated", c); }
        @Bean DomainEventHandler<TrainingForeignEvent> trainingActivityPublished(TrainingConsumers c) { return handler("ActivityPublished", c); }
        @Bean DomainEventHandler<TrainingForeignEvent> trainingActivityCancelled(TrainingConsumers c) { return handler("ActivityCancelled", c); }
        @Bean DomainEventHandler<TrainingForeignEvent> trainingParameterChanged(TrainingConsumers c) { return handler("ParameterChanged", c); }
        @Bean DomainEventHandler<TrainingForeignEvent> trainingRingChanged(TrainingConsumers c) { return handler("RingChanged", c); }
        @Bean DomainEventHandler<TrainingForeignEvent> trainingClubModulesChanged(TrainingConsumers c) { return handler("ClubModulesChanged", c); }
        @Bean DomainEventHandler<TrainingForeignEvent> trainingDogFreeTrainingChanged(TrainingConsumers c) { return handler("DogFreeTrainingChanged", c); }
        @Bean DomainEventHandler<TrainingForeignEvent> trainingDogLevelChanged(TrainingConsumers c) { return handler("DogLevelChanged", c); }
        @Bean DomainEventHandler<TrainingForeignEvent> trainingLevelChanged(TrainingConsumers c) { return handler("LevelChanged", c); }
        @Bean DomainEventHandler<TrainingForeignEvent> trainingWeekOpened(TrainingConsumers c) { return handler("WeekOpened", c); }
        @Bean DomainEventHandler<TrainingForeignEvent> trainingTrainingCounterReset(TrainingConsumers c) { return handler("TrainingCounterReset", c); }
        private DomainEventHandler<TrainingForeignEvent> handler(String type, TrainingConsumers consumers) {
            return new DomainEventHandler<>() {
                public String eventType() { return type; } public Class<TrainingForeignEvent> eventClass() { return TrainingForeignEvent.class; }
                public void handle(String id, TrainingForeignEvent event) { consumers.handle(type, event); }
                // E3-T09 step 5: a class cancelled or a ring blocked shows in the grid right after the commit.
                @Override public boolean evictsAfterCommit() { return true; }
            };
        }
    }
}
