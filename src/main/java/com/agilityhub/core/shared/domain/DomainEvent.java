package com.agilityhub.core.shared.domain;

import java.time.Instant;
import java.util.Map;

public interface DomainEvent {
    String type();
    String clubId();
    String aggregateType();
    String aggregateId();
    Instant occurredAt();
    Map<String, Object> payload();
    String actorAccountId();
    String impersonatedMemberId();
    Origin origin();

    enum Origin { APP, BACKOFFICE, SYSTEM, WEBHOOK }
}
