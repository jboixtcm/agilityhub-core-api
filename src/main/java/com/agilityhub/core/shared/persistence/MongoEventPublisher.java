package com.agilityhub.core.shared.persistence;

import com.agilityhub.core.shared.application.DomainEventHandler;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class MongoEventPublisher extends GlobalRepository<DomainEventRecord> implements EventPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(MongoEventPublisher.class);
    private final ObjectMapper mapper;
    private final MongoDatabaseFactory factory;
    private final Clock clock;
    private final ObjectProvider<DomainEventHandler<?>> handlers;

    public MongoEventPublisher(MongoTemplate mongo, ObjectMapper mapper, MongoDatabaseFactory factory, Clock clock,
            ObjectProvider<DomainEventHandler<?>> handlers) {
        super(mongo, DomainEventRecord.class);
        this.mapper = mapper;
        this.factory = factory;
        this.clock = clock;
        this.handlers = handlers;
    }

    @Override
    public String publish(DomainEvent event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(factory)) {
            throw new IllegalStateException("EventPublisher requires the aggregate's Mongo transaction");
        }
        String id = UUID.randomUUID().toString();
        String json;
        try {
            json = mapper.writeValueAsString(event);
            mongo.insert(new DomainEventRecord(id, event.clubId(), event.type(), event.aggregateType(),
                    event.aggregateId(), event.occurredAt(), event.payload(), event.actorAccountId(),
                    event.impersonatedMemberId(), event.origin(), json,
                    DomainEventRecord.Status.PENDING, 0, clock.instant(), null, null, null, 0, Map.of()));
        } catch (JsonProcessingException invalid) { throw new IllegalArgumentException("Event cannot be serialized", invalid); }
        evictAfterCommit(id, event.type(), json);
        return id;
    }

    /** E3-T09: the cache evictions of this event run on this instance right after the commit ({@link DomainEventHandler#evictsAfterCommit}). */
    private final java.util.concurrent.ConcurrentHashMap<String, List<DomainEventHandler<?>>> evictions = new java.util.concurrent.ConcurrentHashMap<>();
    private void evictAfterCommit(String id, String type, String json) {
        // The handler beans are singletons: the evictions of each event type are looked up once.
        List<DomainEventHandler<?>> local = evictions.computeIfAbsent(type,
                key -> handlers.orderedStream().filter(h -> h.evictsAfterCommit() && h.eventType().equals(key)).toList());
        if (local.isEmpty()) { return; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                for (var handler : local) {
                    try { deliver(handler, id, json); }
                    catch (Exception failure) { LOG.warn("After-commit cache eviction failed type={} eventId={}; the outbox delivery retries it", type, id, failure); }
                }
            }
        });
    }

    private <T> void deliver(DomainEventHandler<T> handler, String id, String json) throws Exception {
        handler.handle(id, mapper.readValue(json, handler.eventClass()));
    }
}
