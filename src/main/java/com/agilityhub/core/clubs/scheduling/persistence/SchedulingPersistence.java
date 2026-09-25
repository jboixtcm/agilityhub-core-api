package com.agilityhub.core.clubs.scheduling.persistence;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Startup setup of the scheduling collections, like the other `*Collections` / `*Indexes` runners. */
@Configuration(proxyBeanMethods = false)
public class SchedulingPersistence {
    /**
     * `ring_slot_locks` must exist before the first transactional upsert (E5-T07): two transactions that both create it
     * implicitly abort each other at commit («namespace already in use»), whatever documents they touch. Another instance
     * that created it meanwhile is not a failure.
     */
    @Bean ApplicationRunner schedulingCollections(MongoTemplate mongo) {
        return args -> {
            if (mongo.collectionExists(RingSlotLock.class)) { return; }
            try { mongo.createCollection(RingSlotLock.class); }
            catch (DataAccessException raced) { if (!mongo.collectionExists(RingSlotLock.class)) { throw raced; } }
        };
    }
}
