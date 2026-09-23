package com.agilityhub.core.shared.domain.events;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

/**
 * S15 §7 events. Shared so the platform framework (SchedulerRun, JobFailed) and the jobs of each
 * owning context (week opening, risk review, reminders) emit one closed envelope.
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown=true)
public record SchedulerEvent(Kind kind, String clubId, String aggregateId, Instant occurredAt,
        Map<String, Object> payload, String actorAccountId, String impersonatedMemberId, Origin origin) implements DomainEvent {
    public SchedulerEvent { payload = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(payload)); }
    @Override public String type() { return kind.name(); }
    @Override public String aggregateType() { return kind.aggregateType; }
    public enum Kind {
        SchedulerRun("JobRun"),
        JobFailed("JobRun"),
        WeekOpened("Week"),
        TrainingCounterReset("Club"),
        ClassAtRisk("ClassSession"),
        ClassAutoCancelled("ClassSession"),
        ClassBelowMinimum("ClassSession"),
        ReminderDue("Booking"),
        WaitlistExpired("WaitlistEntry");
        private final String aggregateType;
        Kind(String aggregateType) { this.aggregateType = aggregateType; }
    }
}
