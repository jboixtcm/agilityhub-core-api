package com.agilityhub.core.shared.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("idempotency_records")
public record IdempotencyRecord(@Id String id, String clubId, String accountId, String key,
                                String requestHash, Status status, int responseStatus, byte[] responseBody,
                                Map<String, List<String>> responseHeaders, Instant createdAt) {
    public enum Status { IN_PROGRESS, DONE }
}
