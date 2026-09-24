package com.agilityhub.core.clubs.scheduling.persistence;

import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.LocalDate;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** R-09-13: the ring-day sequence (see {@link RingDayLock}); always called inside the caller's Mongo transaction. */
@Repository
public class RingDayLockRepository extends TenantRepository<RingDayLock> {
    public RingDayLockRepository(MongoTemplate mongo) { super(mongo, RingDayLock.class); }
    /** `$inc sequence` (upsert) — a concurrent transaction that touches the same ring and day gets a WriteConflict. */
    public void touch(String ringId, LocalDate date) {
        String id = TenantContext.require() + ":" + ringId + ":" + date;
        mongo.upsert(tenantQuery().addCriteria(Criteria.where("_id").is(id)),
                new Update().set("ringId", ringId).set("date", date.toString()).inc("sequence", 1), RingDayLock.class);
    }
}
