package com.agilityhub.core.clubs.bookings.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

/**
 * S10 §7 attendance events. `AttendanceMarked{bookingId, classSessionId, dogId, memberId, state, previousState,
 * by{accountId, role}, late?, afterClassEnd?}` (one per changed item, Annex A payload) and the S15 P3 batch
 * `NoShowNoticeDue{attendanceIds[], bookingIds[]}` (R-10-06, R-15-13). Published through the outbox in the saving transaction.
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record AttendanceEvent(Kind kind, String clubId, String aggregateId, Instant occurredAt,
        Map<String, Object> payload, String actorAccountId, String impersonatedMemberId, Origin origin) implements DomainEvent {
    public AttendanceEvent { payload = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(payload)); }
    @Override public String type() { return kind.name(); }
    @Override public String aggregateType() { return "Attendance"; }
    public enum Kind { AttendanceMarked, NoShowNoticeDue }
}
