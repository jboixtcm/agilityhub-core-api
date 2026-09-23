package com.agilityhub.core.clubs.bookings.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.Duration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;

@Repository
public class SeatHoldRepository extends TenantRepository<SeatHold> {
    public SeatHoldRepository(MongoTemplate mongo) { super(mongo, SeatHold.class); }
    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        var indexes = mongo.indexOps(SeatHold.class);
        indexes.ensureIndex(new Index().on("expiresAt", ASC).expire(Duration.ZERO).named("seat_hold_ttl"));
        indexes.ensureIndex(new Index().on("clubId", ASC).on("classSessionId", ASC).on("dogId", ASC).unique().named("seat_hold_club_class_dog"));
    }
}
