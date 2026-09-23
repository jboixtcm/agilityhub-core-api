package com.agilityhub.core.clubs.bookings.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;

@Repository
public class WaitlistEntryRepository extends TenantRepository<WaitlistEntry> {
    public WaitlistEntryRepository(MongoTemplate mongo) { super(mongo, WaitlistEntry.class); }
    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        var indexes = mongo.indexOps(WaitlistEntry.class);
        indexes.ensureIndex(new Index().on("clubId", ASC).on("classSessionId", ASC).on("state", ASC).named("waitlist_club_class_state"));
        indexes.ensureIndex(new Index().on("clubId", ASC).on("dogId", ASC).on("bookingWeekKey", ASC).on("state", ASC).named("waitlist_club_dog_week_state"));
        // S15 P6 expires NOTIFIED entries whose confirmBy has passed.
        indexes.ensureIndex(new Index().on("clubId", ASC).on("state", ASC).on("confirmBy", ASC).named("waitlist_club_state_confirm"));
    }
}
