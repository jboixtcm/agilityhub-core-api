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
    /**
     * E3-T09: a cache eviction that a user flow must see right after its own write also runs on this instance just after
     * the publishing transaction commits (the outbox delivery stays the backstop, for the other instances and after a
     * crash). Only for idempotent handlers that evict at once, in memory: they run inside `afterCommit`, where a
     * synchronization registered by the handler would never fire.
     */
    default boolean evictsAfterCommit() { return false; }
}
