package com.agilityhub.core.clubs.common.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

public record DataExported(String clubId, String aggregateId, Instant occurredAt, String actorAccountId,
        String listKey, String format, long rows) implements DomainEvent {
    @Override public String type() { return "DataExported"; }
    @Override public String aggregateType() { return "ExportJob"; }
    @Override public Map<String, Object> payload() { return Map.of("listKey", listKey, "format", format, "rows", rows, "by", actorAccountId); }
    @Override public String impersonatedMemberId() { return null; }
    @Override public Origin origin() { return Origin.BACKOFFICE; }
}
