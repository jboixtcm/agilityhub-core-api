package com.agilityhub.core.clubs.bookings.persistence;

import com.agilityhub.core.clubs.bookings.domain.WaitlistState;
import com.agilityhub.core.shared.persistence.TenantRepository;
import java.util.*;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;

@Repository
public class WaitlistEntryRepository extends TenantRepository<WaitlistEntry> {
    public static final List<WaitlistState> LIVE = List.of(WaitlistState.ACTIVE, WaitlistState.NOTIFIED);
    public WaitlistEntryRepository(MongoTemplate mongo) { super(mongo, WaitlistEntry.class); }
    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        var indexes = mongo.indexOps(WaitlistEntry.class);
        indexes.ensureIndex(new Index().on("clubId", ASC).on("classSessionId", ASC).on("state", ASC).named("waitlist_club_class_state"));
        indexes.ensureIndex(new Index().on("clubId", ASC).on("dogId", ASC).on("bookingWeekKey", ASC).on("state", ASC).named("waitlist_club_dog_week_state"));
        // S15 P6 expires NOTIFIED entries whose confirmBy has passed.
        indexes.ensureIndex(new Index().on("clubId", ASC).on("state", ASC).on("confirmBy", ASC).named("waitlist_club_state_confirm"));
    }
    /** Live (ACTIVE or NOTIFIED) entries of a class in FIFO order. */
    public List<WaitlistEntry> live(String classSessionId) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("classSessionId").is(classSessionId).and("state").in(LIVE))
                .with(Sort.by("position", "_id")), WaitlistEntry.class);
    }
    public Optional<WaitlistEntry> live(String classSessionId, String dogId) {
        return Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("classSessionId").is(classSessionId).and("dogId").is(dogId)
                .and("state").in(LIVE)), WaitlistEntry.class));
    }
    /** Every entry of a class, any state, in position order (21/D4/D12 and the S06 cancellation preview). */
    public List<WaitlistEntry> forClass(String classSessionId) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("classSessionId").is(classSessionId)).with(Sort.by("position", "_id")), WaitlistEntry.class);
    }
    /** Live entries of a booking week for the unit (`dogId`, or `memberId` = the dogs' owner with `limitUnit = MEMBER`). */
    public List<WaitlistEntry> liveInWeek(String bookingWeekKey, String field, String value) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where(field).is(value).and("bookingWeekKey").is(bookingWeekKey).and("state").in(LIVE)), WaitlistEntry.class);
    }
    /** Highest position ever given in the class (any state), so a new entry always queues last. */
    public int maxPosition(String classSessionId) {
        var last = mongo.findOne(tenantQuery().addCriteria(Criteria.where("classSessionId").is(classSessionId))
                .with(Sort.by(Sort.Direction.DESC, "position")).limit(1), WaitlistEntry.class);
        return last == null ? 0 : last.position();
    }
    public List<WaitlistEntry> liveForMember(String memberId) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("memberId").is(memberId).and("state").in(LIVE)).with(Sort.by("classStartsAt", "_id")), WaitlistEntry.class);
    }
    /** Live entries whose class has started (S15 P8 sweep, R-15-18a). */
    public List<WaitlistEntry> liveStartedBy(java.time.Instant now) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("state").in(LIVE).and("classStartsAt").lte(now)).with(Sort.by("classSessionId", "_id")), WaitlistEntry.class);
    }
    public WaitlistEntry update(WaitlistEntry next, long expectedVersion) {
        var saved = mongo.findAndReplace(tenantQuery(next.clubId()).addCriteria(Criteria.where("_id").is(next.id()).and("version").is(expectedVersion)),
                next, org.springframework.data.mongodb.core.FindAndReplaceOptions.options().returnNew());
        if (saved == null) { throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.STALE_VERSION); }
        return saved;
    }
    public List<WaitlistEntry> byIds(Collection<String> ids) {
        if (ids.isEmpty()) { return List.of(); }
        return mongo.find(tenantQuery().addCriteria(Criteria.where("_id").in(ids)), WaitlistEntry.class);
    }
}
