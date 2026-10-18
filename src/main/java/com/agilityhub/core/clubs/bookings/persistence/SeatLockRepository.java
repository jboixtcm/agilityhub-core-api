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
    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        mongo.indexOps(SeatLock.class).createIndex(new Index().on("lockedAt", ASC).expire(TTL).named("seat_lock_ttl"));
    }
    /** R-08-07 step 1: `$inc version` (upsert) — a concurrent transaction on the same class gets a WriteConflict. */
    public void lock(String classSessionId) {
        mongo.upsert(tenantQuery().addCriteria(Criteria.where("_id").is(classSessionId)),
                new Update().inc("version", 1).set("lockedAt", clock.instant()), SeatLock.class);
    }
}
