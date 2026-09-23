package com.agilityhub.core.clubs.bookings.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/** Local/test demo registrant of a class (E4-T05). E5 replaces this collection with the real S08 `bookings`/`waitlist_entries`. */
@Document("demo_class_bookings")
public record DemoClassBooking(@Id String id, String clubId, String classId, String memberId, String dogId, State state, boolean paidWithPack,
        Integer position, String cancelReason, Instant cancelledAt, @Version Long version, Instant createdAt, Instant updatedAt) implements TenantEntity {
    public enum State { ACTIVE, WAITLISTED, CANCELLED_BY_CLUB, CANCELLED }
    public DemoClassBooking cancelled(String reason, Instant now) {
        return new DemoClassBooking(id, clubId, classId, memberId, dogId, state == State.ACTIVE ? State.CANCELLED_BY_CLUB : State.CANCELLED,
                paidWithPack, position, reason, now, version + 1, createdAt, now);
    }
}
