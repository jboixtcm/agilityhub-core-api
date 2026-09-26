package com.agilityhub.core.clubs.bookings.persistence;

import com.agilityhub.core.clubs.bookings.domain.AttendanceState;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.FindAndReplaceOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;

/**
 * S10 §3 `attendances`: one document per booking, created on its first effective save (no document = PENDING). Every
 * write runs inside the attendance save transaction, which holds the class's `seat_locks` row (R-10-04), or inside the
 * N-19 claim (R-10-06).
 */
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
    public List<Attendance> findByClassSession(String classSessionId) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("classSessionId").is(classSessionId)).with(Sort.by("_id")), Attendance.class);
    }
    public Optional<Attendance> findByBooking(String bookingId) {
        return Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("bookingId").is(bookingId)), Attendance.class));
    }
    /** One `$in` read for the bookings of a page, a card or a history. */
    public Map<String, Attendance> byBookings(Collection<String> bookingIds) {
        if (bookingIds.isEmpty()) { return Map.of(); }
        var result = new HashMap<String, Attendance>();
        mongo.find(tenantQuery().addCriteria(Criteria.where("bookingId").in(bookingIds)), Attendance.class).forEach(a -> result.put(a.bookingId(), a));
        return result;
    }
    /** The rows of a `GET /attendances` page, in one read. */
    public List<Attendance> byIds(Collection<String> ids) {
        if (ids.isEmpty()) { return List.of(); }
        return mongo.find(tenantQuery().addCriteria(Criteria.where("_id").in(ids)), Attendance.class);
    }
    /** The attendances of a dog whose class starts at or after {@code from} (22/D13, index `{clubId, dogId, classStartsAt}`). */
    public List<Attendance> findByDogSince(String dogId, Instant from) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("dogId").is(dogId).and("classStartsAt").gte(from)).with(Sort.by("classStartsAt", "_id")), Attendance.class);
    }
    /** `AttendanceStatePort` (S08 `displayState` and the R-08-10 precondition). */
    public Optional<AttendanceState> stateOf(String bookingId) { return findByBooking(bookingId).map(Attendance::state); }

    /**
     * Inserts the first mark of a booking, or replaces the stored one when its `version` is still {@code expected}
     * (compare-and-set; the unique `{clubId, bookingId}` index refuses a second first mark).
     */
    public Attendance upsert(Attendance next, Long expected) {
        if (expected == null) { return insert(next); }
        var saved = mongo.findAndReplace(tenantQuery(next.clubId()).addCriteria(Criteria.where("_id").is(next.id()).and("version").is(expected)),
                next, FindAndReplaceOptions.options().returnNew());
        if (saved == null) { throw new ApiException(ErrorCode.STALE_VERSION); }
        return saved;
    }

    /**
     * R-10-06 / S15 R-15-13: claims, one `findAndModify` at a time, every NO_SHOW of the club whose notice is not queued
     * yet and whose club-local `classDate` is before {@code today} (`classDate` is stored `YYYY-MM-DD`, so the text order
     * is the date order), writing `noShowNotice.queuedAt` and the batch token in `noShowNotice.eventId`. A second run
     * finds nothing: the claimed rows no longer match.
     */
    public List<Attendance> claimForNoShowNotice(String clubId, LocalDate today, Instant now, String batchToken) {
        var query = tenantQuery(clubId).addCriteria(Criteria.where("state").is(AttendanceState.NO_SHOW).and("noShowNotice.queuedAt").is(null)
                .and("classDate").lt(today)).with(Sort.by("classDate", "_id"));
        var update = new Update().set("noShowNotice.queuedAt", now).set("noShowNotice.eventId", batchToken).set("updatedAt", now).inc("version", 1);
        var claimed = new ArrayList<Attendance>();
        for (Attendance next; (next = mongo.findAndModify(query, update, FindAndModifyOptions.options().returnNew(true), Attendance.class)) != null; ) {
            claimed.add(next);
        }
        return claimed;
    }
    /** Replaces the claim's batch token with the id of the `NoShowNoticeDue` the claim published (same transaction). */
    public void noticeEvent(String batchToken, String eventId) {
        mongo.updateMulti(tenantQuery().addCriteria(Criteria.where("noShowNotice.eventId").is(batchToken)), new Update().set("noShowNotice.eventId", eventId),
                Attendance.class);
    }
    /** `ClassSessionUpdated` (S10 §7): the denormalised class times and date of the class's attendances; idempotent. */
    public long refreshClass(String classSessionId, LocalDate classDate, Instant classStartsAt, Instant classEndsAt) {
        var stale = new Criteria().orOperator(Criteria.where("classDate").ne(classDate), Criteria.where("classStartsAt").ne(classStartsAt),
                Criteria.where("classEndsAt").ne(classEndsAt));
        return mongo.updateMulti(tenantQuery().addCriteria(Criteria.where("classSessionId").is(classSessionId)).addCriteria(stale),
                new Update().set("classDate", classDate).set("classStartsAt", classStartsAt).set("classEndsAt", classEndsAt).inc("version", 1),
                Attendance.class).getModifiedCount();
    }
    /** Appends a trail entry without changing the mark (S10 §7: a contradicting `BookingCancelled`). */
    public void appendHistory(String bookingId, Attendance.Change change) {
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("bookingId").is(bookingId)), new Update().push("history", change).inc("version", 1),
                Attendance.class);
    }
}
