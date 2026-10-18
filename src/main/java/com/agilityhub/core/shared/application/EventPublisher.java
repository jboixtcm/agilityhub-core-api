package com.agilityhub.core.shared.application;

import com.agilityhub.core.shared.domain.DomainEvent;

public interface EventPublisher {
    String publish(DomainEvent event);
    /**
     * E11-T10 (INC-38, S14 R-14-10): the ids of the events this publisher wrote in the current transaction so far, in order, for
     * the `AuditEntry.eventIds[]` written in the same transaction; none outside a transaction.
     */
    default java.util.List<String> publishedInTransaction() { return java.util.List.of(); }
}
