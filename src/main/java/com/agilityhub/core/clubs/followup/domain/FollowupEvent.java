package com.agilityhub.core.clubs.followup.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

/**
 * S10 §7 task and attachment events: `TaskCreated{taskId, dogId, memberId, by}` (N-20), `TaskUpdated`, `TaskDeleted`,
 * `TaskCompleted{taskId, dogId, memberId, by{accountId, role}}` (N-21), `TaskReopened{taskId, dogId, by}` and
 * `AttachmentRemoved{attachmentId, entityType, entityId}` (both in CATALEG_ESDEVENIMENTS Annex A). `AttachmentAdded`
 * keeps its own record (E2-T06). Published through the outbox in the writing transaction.
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record FollowupEvent(Kind kind, String clubId, String aggregateId, Instant occurredAt,
        Map<String, Object> payload, String actorAccountId, String impersonatedMemberId, Origin origin) implements DomainEvent {
    public FollowupEvent { payload = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(payload)); }
    @Override public String type() { return kind.name(); }
    @Override public String aggregateType() { return kind.aggregateType; }
    public enum Kind {
        TaskCreated("Task"), TaskUpdated("Task"), TaskDeleted("Task"), TaskCompleted("Task"), TaskReopened("Task"),
        AttachmentRemoved("Attachment");
        private final String aggregateType;
        Kind(String aggregateType) { this.aggregateType = aggregateType; }
    }
}
