package com.agilityhub.core.clubs.scheduling.persistence;

import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.Instant;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/**
 * R-09-13: the ring-slot sequence (see {@link RingSlotLock}); always called inside the caller's Mongo transaction. The
 * collection is created at startup by {@link SchedulingPersistence#schedulingCollections}.
 */
@Repository
public class RingSlotLockRepository extends TenantRepository<RingSlotLock> {
    public RingSlotLockRepository(MongoTemplate mongo) { super(mongo, RingSlotLock.class); }
    /** `$inc sequence` (upsert) — a concurrent transaction that touches the same ring slot gets a WriteConflict. */
    public void touch(String ringId, Instant startsAt) {
        String id = TenantContext.require() + ":" + ringId + ":" + startsAt;
        mongo.upsert(tenantQuery().addCriteria(Criteria.where("_id").is(id)),
                new Update().set("ringId", ringId).set("startsAt", startsAt).inc("sequence", 1), RingSlotLock.class);
    }
}
