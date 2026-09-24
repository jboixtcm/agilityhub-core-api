package com.agilityhub.core.clubs.bookings.persistence;

import com.agilityhub.core.clubs.bookings.domain.BookingState;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndReplaceOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;

@Repository
public class BookingRepository extends TenantRepository<Booking> {
    public static final List<BookingState> LIVE = List.of(BookingState.ACTIVE, BookingState.PAYMENT_PENDING);
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
    public Booking require(String id) { return findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)); }
    /** Bookings of a class in the given states (ACTIVE + PAYMENT_PENDING = the seats taken). */
    public List<Booking> forClass(String classSessionId, Collection<BookingState> states) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("classSessionId").is(classSessionId).and("state").in(states))
                .with(Sort.by("bookedAt", "_id")), Booking.class);
    }
    public List<Booking> forClass(String classSessionId) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("classSessionId").is(classSessionId)).with(Sort.by("bookedAt", "_id")), Booking.class);
    }
    public Optional<Booking> live(String classSessionId, String dogId) {
        return Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("classSessionId").is(classSessionId).and("dogId").is(dogId)
                .and("state").in(LIVE)), Booking.class));
    }
    /** Every booking of a booking week for the unit (the dog, or all dogs of the owner). */
    public List<Booking> week(String bookingWeekKey, String field, String value) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where(field).is(value).and("bookingWeekKey").is(bookingWeekKey)), Booking.class);
    }
    public List<Booking> forDogs(Collection<String> dogIds, Collection<BookingState> states, Instant from, Instant to) {
        var criteria = Criteria.where("dogId").in(dogIds);
        if (states != null && !states.isEmpty()) { criteria = criteria.and("state").in(states); }
        if (from != null || to != null) {
            var range = Criteria.where("classStartsAt");
            if (from != null) { range = range.gte(from); }
            if (to != null) { range = range.lt(to); }
            criteria = new Criteria().andOperator(criteria, range);
        }
        return mongo.find(tenantQuery().addCriteria(criteria).with(Sort.by("classStartsAt", "_id")), Booking.class);
    }
    public List<Booking> startingBetween(Instant from, Instant until, Collection<BookingState> states) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("classStartsAt").gte(from).lt(until).and("state").in(states)), Booking.class);
    }
    /** Live bookings of a dog whose class overlaps `[from, to)` (S09 R-09-06 step 3). */
    public List<Booking> liveOverlapping(String dogId, Instant from, Instant to) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("dogId").is(dogId).and("state").in(LIVE).and("classStartsAt").lt(to).and("classEndsAt").gt(from)),
                Booking.class);
    }
    public List<Booking> liveForMember(String memberId, Instant after) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("memberId").is(memberId).and("state").in(LIVE).and("classStartsAt").gt(after))
                .with(Sort.by("classStartsAt", "_id")), Booking.class);
    }
    public List<Booking> byIds(Collection<String> ids) {
        if (ids.isEmpty()) { return List.of(); }
        return mongo.find(tenantQuery().addCriteria(Criteria.where("_id").in(ids)), Booking.class);
    }
    /** S15 P7: PAYMENT_PENDING bookings booked at or before `cutoff` (`bookedAt + bookings.paymentPendingMinutes ≤ now`). */
    public List<Booking> pendingSince(Instant cutoff) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("state").is(BookingState.PAYMENT_PENDING).and("bookedAt").lte(cutoff))
                .with(Sort.by("bookedAt", "_id")), Booking.class);
    }
    public Optional<Booking> byCheckoutSession(String sessionId) {
        return Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("charge.checkoutSessionId").is(sessionId)), Booking.class));
    }
    /** Compare-and-set on `version` (the booking transaction also serialises the class through `seat_locks`). */
    public Booking update(Booking next, long expectedVersion) {
        var saved = mongo.findAndReplace(tenantQuery(next.clubId()).addCriteria(Criteria.where("_id").is(next.id()).and("version").is(expectedVersion)),
                next, FindAndReplaceOptions.options().returnNew());
        if (saved == null) { throw new ApiException(ErrorCode.STALE_VERSION); }
        return saved;
    }
}
