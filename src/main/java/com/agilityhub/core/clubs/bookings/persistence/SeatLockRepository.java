package com.agilityhub.core.clubs.bookings.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

/** `_id = classSessionId`; the clubId predicate keeps a class of another tenant out of reach. */
@Repository
public class SeatLockRepository extends TenantRepository<SeatLock> {
    public SeatLockRepository(MongoTemplate mongo) { super(mongo, SeatLock.class); }
}
