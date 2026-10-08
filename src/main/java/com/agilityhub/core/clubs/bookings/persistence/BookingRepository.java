package com.agilityhub.core.clubs.bookings.persistence;

import com.agilityhub.core.clubs.bookings.domain.BookingState;
import com.agilityhub.core.clubs.bookings.domain.ChargeMode;
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
    /** {@link #forClass(String, Collection)} for many classes in one `$in` read (the D1 risk card, E5-T10). */
    public List<Booking> forClasses(Collection<String> classSessionIds, Collection<BookingState> states) {
        if (classSessionIds.isEmpty()) { return List.of(); }
        return mongo.find(tenantQuery().addCriteria(Criteria.where("classSessionId").in(classSessionIds).and("state").in(states))
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
    /** Lifecycle selection must use the class's own time, so do not prefilter by this asynchronous projection. */
    public List<Booking> liveForMember(String memberId) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("memberId").is(memberId).and("state").in(LIVE))
                .with(Sort.by("classSessionId", "_id")), Booking.class);
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
    /**
     * S15 R-15-14 P4 scope: ACTIVE bookings whose class starts in `(after, until]` and that have no reminder yet (index
     * `booking_club_state_starts`), by start.
     */
    public List<Booking> reminderScope(Instant after, Instant until) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("state").is(BookingState.ACTIVE).and("classStartsAt").gt(after).lte(until)
                .and("reminderSentAt").is(null)).with(Sort.by("classStartsAt", "_id")), Booking.class);
    }
    /**
     * S15 R-15-14 P4 mark, set once: only on a booking still ACTIVE, without a reminder and starting at the planned instant.
     * `version` is left alone, so a member's concurrent change never fails on it (a concurrent transaction conflicts and retries).
     */
    public boolean markReminderSent(String id, Instant classStartsAt, Instant at) {
        var query = tenantQuery().addCriteria(Criteria.where("_id").is(id).and("state").is(BookingState.ACTIVE).and("reminderSentAt").is(null)
                .and("classStartsAt").is(classStartsAt));
        return mongo.updateFirst(query, new org.springframework.data.mongodb.core.query.Update().set("reminderSentAt", at), Booking.class).getModifiedCount() == 1;
    }
    /**
     * S12 R-12-25 (E8-T02): the provisional `charge.chargeInvoiceLineRef` of a `CHARGE_ON_ATTENDANCE` booking — its `PendingCharge` —
     * written by the collection name, so `version` is left alone and no member's edit of the booking fails on it.
     */
    public boolean stampChargeRef(String id, String reference) {
        var query = tenantQuery().addCriteria(Criteria.where("_id").is(id).and("charge.mode").is(ChargeMode.CHARGE_ON_ATTENDANCE.name())
                .and("charge.chargeInvoiceLineRef").ne(reference));
        return mongo.updateFirst(query, new org.springframework.data.mongodb.core.query.Update().set("charge.chargeInvoiceLineRef", reference), "bookings")
                .getModifiedCount() == 1;
    }
    /** S12 R-12-25 (E8-T02 round 2): the booking stops naming {@code reference}, its voided charge; another reference is kept. */
    public boolean clearChargeRef(String id, String reference) {
        var query = tenantQuery().addCriteria(Criteria.where("_id").is(id).and("charge.mode").is(ChargeMode.CHARGE_ON_ATTENDANCE.name())
                .and("charge.chargeInvoiceLineRef").is(reference));
        return mongo.updateFirst(query, new org.springframework.data.mongodb.core.query.Update().set("charge.chargeInvoiceLineRef", null), "bookings")
                .getModifiedCount() == 1;
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
