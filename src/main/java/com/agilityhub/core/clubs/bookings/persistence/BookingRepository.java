package com.agilityhub.core.clubs.bookings.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;

@Repository
public class BookingRepository extends TenantRepository<Booking> {
    public BookingRepository(MongoTemplate mongo) { super(mongo, Booking.class); }
    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        var indexes = mongo.indexOps(Booking.class);
        indexes.ensureIndex(new Index().on("clubId", ASC).on("classSessionId", ASC).on("dogId", ASC).on("state", ASC).named("booking_club_class_dog_state"));
        indexes.ensureIndex(new Index().on("clubId", ASC).on("dogId", ASC).on("bookingWeekKey", ASC).on("state", ASC).named("booking_club_dog_week_state"));
        indexes.ensureIndex(new Index().on("clubId", ASC).on("memberId", ASC).on("bookingWeekKey", ASC).on("state", ASC).named("booking_club_member_week_state"));
        // S15 P4 reminders and P7 payment timeouts scan by state and class start.
        indexes.ensureIndex(new Index().on("clubId", ASC).on("state", ASC).on("classStartsAt", ASC).named("booking_club_state_starts"));
    }
}
