package com.agilityhub.core.clubs.common.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** S14 §7 `DataExported{listKey, format, rows, by, jobId, kind}`: every export job at `READY` (a list, the accounting file, a member's package). */
public record DataExported(String clubId, String aggregateId, Instant occurredAt, String actorAccountId,
        String listKey, String format, long rows, String kind) implements DomainEvent {
    public DataExported(String clubId, String aggregateId, Instant occurredAt, String actorAccountId, String listKey, String format, long rows) {
        this(clubId, aggregateId, occurredAt, actorAccountId, listKey, format, rows, "accounting".equals(listKey) ? "ACCOUNTING" : "LIST");
    }
    @Override public String type() { return "DataExported"; }
    @Override public String aggregateType() { return "ExportJob"; }
    @Override public Map<String, Object> payload() {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("listKey", listKey); payload.put("format", format); payload.put("rows", rows);
        if (actorAccountId != null) { payload.put("by", actorAccountId); }
        payload.put("jobId", aggregateId); payload.put("kind", kind);
        return payload;
    }
    @Override public String impersonatedMemberId() { return null; }
    @Override public Origin origin() { return Origin.BACKOFFICE; }
}
