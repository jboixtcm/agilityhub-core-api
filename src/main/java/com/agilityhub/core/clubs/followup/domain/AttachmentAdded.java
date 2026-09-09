package com.agilityhub.core.clubs.followup.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

public record AttachmentAdded(String clubId, String aggregateId, Instant occurredAt, Map<String,Object> payload,
        String actorAccountId, String impersonatedMemberId, Origin origin) implements DomainEvent {
    @Override public String type() { return "AttachmentAdded"; }
    @Override public String aggregateType() { return "Attachment"; }
}
