package com.agilityhub.core.shared.application;

import com.agilityhub.core.shared.domain.DomainEvent;

/** External side effects must be idempotent using eventId. Bean names are durable consumer IDs. */
public interface DomainEventHandler<T extends DomainEvent> {
    String eventType();
    Class<T> eventClass();
    void handle(String eventId, T event) throws Exception;
}
