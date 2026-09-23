package com.agilityhub.core.clubs.bookings.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** `_id = classSessionId`; the clubId predicate keeps a class of another tenant out of reach. */
@Repository
public class SeatLockRepository extends TenantRepository<SeatLock> {
    public SeatLockRepository(MongoTemplate mongo) { super(mongo, SeatLock.class); }
    /** R-08-07 step 1: `$inc version` (upsert) — a concurrent transaction on the same class gets a WriteConflict. */
    public void lock(String classSessionId) {
        mongo.upsert(tenantQuery().addCriteria(Criteria.where("_id").is(classSessionId)), new Update().inc("version", 1), SeatLock.class);
    }
}
