package com.agilityhub.core.platform.domain.events;

import com.agilityhub.core.platform.domain.ImmutableValues;
import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

/**
 * S17 §7 / event catalog Annex A (E10-T03): `{clubId, host}` when S15 P9's re-check finds a verified club domain failing for the
 * third time in a row and makes it `BROKEN` (R-17-03); N-43 tells the platform admins. A system process's: no actor, `SYSTEM`.
 */
public record ClubDomainBroken(String clubId, Instant occurredAt, Map<String, Object> payload,
        String actorAccountId, String impersonatedMemberId, Origin origin) implements DomainEvent {
    public ClubDomainBroken { payload = ImmutableValues.map(payload); }
    public ClubDomainBroken(String clubId, String host, Instant occurredAt) {
        this(clubId, occurredAt, Map.of("clubId", clubId, "host", host), null, null, Origin.SYSTEM);
    }
    @Override public String type() { return "ClubDomainBroken"; }
    @Override public String aggregateType() { return "Club"; }
    @Override public String aggregateId() { return clubId; }
}
