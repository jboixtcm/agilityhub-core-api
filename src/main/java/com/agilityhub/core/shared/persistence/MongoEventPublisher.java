package com.agilityhub.core.shared.persistence;

import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class MongoEventPublisher extends TenantRepository<DomainEventRecord> implements EventPublisher {
    private final ObjectMapper mapper;
    private final MongoDatabaseFactory factory;
    private final Clock clock;

    public MongoEventPublisher(MongoTemplate mongo, ObjectMapper mapper, MongoDatabaseFactory factory, Clock clock) {
        super(mongo);
        this.mapper = mapper;
        this.factory = factory;
        this.clock = clock;
    }

    @Override
    public String publish(DomainEvent event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(factory)) {
            throw new IllegalStateException("EventPublisher requires the aggregate's Mongo transaction");
        }
        String id = UUID.randomUUID().toString();
        try {
            mongo.insert(new DomainEventRecord(id, event.clubId(), event.type(), event.aggregateType(),
                    event.aggregateId(), event.occurredAt(), event.payload(), event.actorAccountId(),
                    event.impersonatedMemberId(), event.origin(), mapper.writeValueAsString(event),
                    DomainEventRecord.Status.PENDING, 0, clock.instant(), null, null, null, 0, Map.of()));
        } catch (JsonProcessingException invalid) { throw new IllegalArgumentException("Event cannot be serialized", invalid); }
        return id;
    }
}
