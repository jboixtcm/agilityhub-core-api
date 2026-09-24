package com.agilityhub.core.clubs.bookings.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;

/** S10 §3 `attendances`; the sheet, the N-19 batch claim and the queries arrive with E6-T02. */
@Repository
public class AttendanceRepository extends TenantRepository<Attendance> {
    public AttendanceRepository(MongoTemplate mongo) { super(mongo, Attendance.class); }
    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        var indexes = mongo.indexOps(Attendance.class);
        indexes.ensureIndex(new Index().on("clubId", ASC).on("bookingId", ASC).unique().named("attendance_club_booking"));
        indexes.ensureIndex(new Index().on("clubId", ASC).on("classSessionId", ASC).named("attendance_club_class"));
        indexes.ensureIndex(new Index().on("clubId", ASC).on("dogId", ASC).on("classStartsAt", ASC).named("attendance_club_dog_starts"));
        // R-10-06: the P3 claim reads NO_SHOW rows not yet queued with an earlier class date.
        indexes.ensureIndex(new Index().on("clubId", ASC).on("state", ASC).on("noShowNotice.queuedAt", ASC).on("classDate", ASC)
                .named("attendance_club_state_notice_date"));
    }
}
