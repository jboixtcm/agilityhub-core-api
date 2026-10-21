package com.agilityhub.core.clubs.bookings.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.Clock;
import java.time.Duration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;

/** `_id = classSessionId`; the clubId predicate keeps a class of another tenant out of reach. */
@Repository
public class SeatLockRepository extends TenantRepository<SeatLock> {
    /**
     * S08 §3, R-15-19 (INC-40, E11-T10): a lock is only a write-conflict anchor, so one no write touched for 7 days goes. A class
     * whose lock was removed is in the state of a class never locked: its next lock() upserts it again, and a TTL delete racing
     * an open transaction on the same document is a write conflict that {@code BookingTransactions} already retries.
     */
    public static final Duration TTL = Duration.ofDays(7);
    private final Clock clock;
    public SeatLockRepository(MongoTemplate mongo, Clock clock) { super(mongo, SeatLock.class); this.clock = clock; }
    /**
     * The TTL index, and (E11-T17, N2 of E11-T10's review) `lockedAt = now` on every lock written before the field existed, in
     * every club: without it the TTL never drops such a lock and P9 never counts it. Start-up only and idempotent (a lock that
     * has `lockedAt` keeps it); a booking's `$inc` racing it on the same lock is a write conflict its transaction retries.
     */
    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        mongo.indexOps(SeatLock.class).createIndex(new Index().on("lockedAt", ASC).expire(TTL).named("seat_lock_ttl"));
        mongo.updateMulti(new org.springframework.data.mongodb.core.query.Query(Criteria.where("lockedAt").exists(false)), new Update().set("lockedAt", clock.instant()), SeatLock.class);
    }
    /** R-08-07 step 1: `$inc version` (upsert) — a concurrent transaction on the same class gets a WriteConflict. */
    public void lock(String classSessionId) {
        mongo.upsert(tenantQuery().addCriteria(Criteria.where("_id").is(classSessionId)),
                new Update().inc("version", 1).set("lockedAt", clock.instant()), SeatLock.class);
    }
}
