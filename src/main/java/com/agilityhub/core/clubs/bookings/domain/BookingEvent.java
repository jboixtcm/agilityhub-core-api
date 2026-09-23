package com.agilityhub.core.clubs.bookings.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

/** S08 §7 catalog events; publication stays inside the booking transaction. */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown=true)
public record BookingEvent(Kind kind, String clubId, String aggregateId, Instant occurredAt,
        Map<String, Object> payload, String actorAccountId, String impersonatedMemberId, Origin origin) implements DomainEvent {
    public BookingEvent { payload = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(payload)); }
    @Override public String type() { return kind.name(); }
    @Override public String aggregateType() { return kind.aggregateType; }
    public enum Kind {
        SeatHeld("SeatHold"),
        SeatHoldReleased("SeatHold"),
        BookingCreated("Booking"),
        BookingCancelled("Booking"),
        SeatReleased("ClassSession"),
        WaitlistJoined("WaitlistEntry"),
        WaitlistLeft("WaitlistEntry"),
        WaitlistNotified("ClassSession"),
        WaitlistConsolidated("WaitlistEntry");
        private final String aggregateType;
        Kind(String aggregateType) { this.aggregateType = aggregateType; }
    }
}
