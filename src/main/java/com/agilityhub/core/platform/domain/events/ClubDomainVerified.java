package com.agilityhub.core.platform.domain.events;

import com.agilityhub.core.platform.domain.ImmutableValues;
import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

/** S17 §7 / event catalog Annex A (E10-T04): `{clubId, host}` once a club domain is verified (R-17-03). */
public record ClubDomainVerified(String clubId, Instant occurredAt, Map<String, Object> payload,
        String actorAccountId, String impersonatedMemberId, Origin origin) implements DomainEvent {
    public ClubDomainVerified { payload = ImmutableValues.map(payload); }
    public ClubDomainVerified(String clubId, String host, Instant occurredAt, String actorAccountId) {
        this(clubId, occurredAt, Map.of("clubId", clubId, "host", host), actorAccountId, null, Origin.BACKOFFICE);
    }
    /** E10-T03: S15 P9's re-check found a `BROKEN` domain working again (no actor, `SYSTEM`). */
    public static ClubDomainVerified restored(String clubId, String host, Instant occurredAt) {
        return new ClubDomainVerified(clubId, occurredAt, Map.of("clubId", clubId, "host", host), null, null, Origin.SYSTEM);
    }
    @Override public String type() { return "ClubDomainVerified"; }
    @Override public String aggregateType() { return "Club"; }
    @Override public String aggregateId() { return clubId; }
}
