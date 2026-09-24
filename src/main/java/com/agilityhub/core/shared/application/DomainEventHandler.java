package com.agilityhub.core.shared.application;

/**
 * External side effects must be idempotent using eventId. Bean names are durable consumer IDs. {@code T} is the
 * published event class or a read-only consumer envelope (`*ForeignEvent`), which is deliberately not a
 * {@link com.agilityhub.core.shared.domain.DomainEvent} so that it cannot be published (E5-T09).
 */
public interface DomainEventHandler<T> {
    String eventType();
    Class<T> eventClass();
    void handle(String eventId, T event) throws Exception;
}
