package com.agilityhub.core.clubs.bookings.persistence;

import com.agilityhub.core.shared.application.MemberMergeParticipant;
import com.agilityhub.core.shared.persistence.TenantRepository;
import java.util.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;

/** Moves operational ownership without changing any historical event or movement. */
@Repository
public class BookingMergeRepository extends TenantRepository<Booking> implements MemberMergeParticipant {
    private final java.time.Clock clock;
    public BookingMergeRepository(MongoTemplate mongo, java.time.Clock clock) { super(mongo, Booking.class); this.clock = clock; }
    private Query selected(String memberId, String[] states) {
        var query = tenantQuery().addCriteria(Criteria.where("memberId").is(memberId));
        if (states != null) query.addCriteria(Criteria.where("state").in((Object[]) states));
        return query;
    }
    @Override public Map<String, Long> preview(String sourceMemberId) {
        var counts = new LinkedHashMap<String, Long>();
        counts.put("classBookings", mongo.count(selected(sourceMemberId, new String[]{"ACTIVE", "PAYMENT_PENDING"}).addCriteria(Criteria.where("classEndsAt").gt(clock.instant())), Booking.class));
        counts.put("waitlist", mongo.count(selected(sourceMemberId, new String[]{"ACTIVE", "NOTIFIED"}), WaitlistEntry.class));
        counts.put("seatHolds", mongo.count(selected(sourceMemberId, null), SeatHold.class));
        return counts;
    }
    @Override public void merge(String sourceMemberId, String targetMemberId) {
        mongo.updateMulti(selected(sourceMemberId, new String[]{"ACTIVE", "PAYMENT_PENDING"}).addCriteria(Criteria.where("classEndsAt").gt(clock.instant())), new Update().set("memberId", targetMemberId).inc("version", 1), Booking.class);
        mongo.updateMulti(selected(sourceMemberId, new String[]{"ACTIVE", "NOTIFIED"}), new Update().set("memberId", targetMemberId).inc("version", 1), WaitlistEntry.class);
        mongo.updateMulti(selected(sourceMemberId, null), new Update().set("memberId", targetMemberId).inc("version", 1), SeatHold.class);
    }
}
