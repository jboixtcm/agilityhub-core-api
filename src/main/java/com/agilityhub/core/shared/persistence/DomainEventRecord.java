package com.agilityhub.core.shared.persistence;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("domain_events")
public record DomainEventRecord(@Id String id, String clubId, String type, String aggregateType,
                                String aggregateId, Instant occurredAt, Map<String, Object> payload,
                                String actorAccountId, String impersonatedMemberId, DomainEvent.Origin origin,
                                String eventJson, Status status, int attempts, Instant nextAttemptAt,
                                Instant publishedAt, String error, String claimToken, int lockVersion, Map<String, Instant> processedAt) {
    public enum Status { PENDING, PUBLISHED, FAILED }
}
