package com.agilityhub.core.clubs.scheduling.persistence;

import java.time.Duration;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexInfo;

/** Startup setup of the scheduling collections, like the other `*Collections` / `*Indexes` runners. */
@Configuration(proxyBeanMethods = false)
public class SchedulingPersistence {
    /** The TTL index of `ring_slot_locks` on `expiresAt` (`expireAfterSeconds: 0`, S15 R-15-19 amended 25-09). */
    static final String RING_SLOT_TTL = "ring_slot_lock_ttl";
    private static final String EXPIRES_AT = "expiresAt";

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
            try { indexes.ensureIndex(new Index().on(EXPIRES_AT, Sort.Direction.ASC).expire(Duration.ZERO).named(RING_SLOT_TTL)); }
            catch (DataAccessException raced) {
                // Tolerated only when the index now stands with the same key and TTL: a conflicting definition stays a startup failure.
                if (indexes.getIndexInfo().stream().noneMatch(SchedulingPersistence::ringSlotTtl)) { throw raced; }
            }
        };
    }

    /**
     * The index this runner ensures: its name, the key `{expiresAt: 1}` alone and a 0 s TTL (E5-T18, review E5-T17 #4). The
     * same name on another key would never expire the locks.
     */
    private static boolean ringSlotTtl(IndexInfo index) {
        var fields = index.getIndexFields();
        return RING_SLOT_TTL.equals(index.getName()) && index.getExpireAfter().filter(Duration.ZERO::equals).isPresent()
                && fields.size() == 1 && EXPIRES_AT.equals(fields.getFirst().getKey()) && fields.getFirst().getDirection() == Sort.Direction.ASC;
    }
}
