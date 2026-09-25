package com.agilityhub.core.clubs.scheduling.persistence;

import java.time.Duration;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

/** Startup setup of the scheduling collections, like the other `*Collections` / `*Indexes` runners. */
@Configuration(proxyBeanMethods = false)
public class SchedulingPersistence {
    /** The TTL index of `ring_slot_locks` on `expiresAt` (`expireAfterSeconds: 0`, S15 R-15-19 amended 25-09). */
    static final String RING_SLOT_TTL = "ring_slot_lock_ttl";

    /**
     * `ring_slot_locks` must exist before the first transactional upsert (E5-T07): two transactions that both create it
     * implicitly abort each other at commit («namespace already in use»), whatever documents they touch. Another instance
     * that created it meanwhile is not a failure. Then its TTL index (E5-T17), on every start: creating an index that
     * exists with the same options is a no-op, and a concurrent identical build by another instance is not a failure.
     */
    @Bean ApplicationRunner schedulingCollections(MongoTemplate mongo) {
        return args -> {
            if (!mongo.collectionExists(RingSlotLock.class)) {
                try { mongo.createCollection(RingSlotLock.class); }
                catch (DataAccessException raced) { if (!mongo.collectionExists(RingSlotLock.class)) { throw raced; } }
            }
            var indexes = mongo.indexOps(RingSlotLock.class);
            try { indexes.ensureIndex(new Index().on("expiresAt", Sort.Direction.ASC).expire(Duration.ZERO).named(RING_SLOT_TTL)); }
            catch (DataAccessException raced) {
                // Tolerated only when the index now stands with the same TTL: a conflicting definition stays a startup failure.
                if (indexes.getIndexInfo().stream().noneMatch(index -> RING_SLOT_TTL.equals(index.getName())
                        && index.getExpireAfter().filter(Duration.ZERO::equals).isPresent())) { throw raced; }
            }
        };
    }
}
