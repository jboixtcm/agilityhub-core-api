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
        indexes.createIndex(new Index().on("clubId", ASC).on("classSessionId", ASC).on("state", ASC).named("waitlist_club_class_state"));
        indexes.createIndex(new Index().on("clubId", ASC).on("dogId", ASC).on("bookingWeekKey", ASC).on("state", ASC).named("waitlist_club_dog_week_state"));
        // S15 P6 expires NOTIFIED entries whose confirmBy has passed.
        indexes.createIndex(new Index().on("clubId", ASC).on("state", ASC).on("confirmBy", ASC).named("waitlist_club_state_confirm"));
        // INC-46 (E11-T02): S15 P8 reads the live entries whose copy says the class started.
        indexes.createIndex(new Index().on("clubId", ASC).on("state", ASC).on("classStartsAt", ASC).named("waitlist_club_state_starts"));
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
    /** Live entries of the given dogs whose class starts after {@code after} (03 `CLASS_WAITLIST` rows, 04 exclusions). */
    public List<WaitlistEntry> liveForDogs(Collection<String> dogIds, java.time.Instant after) {
        if (dogIds.isEmpty()) { return List.of(); }
        return mongo.find(tenantQuery().addCriteria(Criteria.where("dogId").in(dogIds).and("state").in(LIVE).and("classStartsAt").gt(after))
                .with(Sort.by("classStartsAt", "_id")), WaitlistEntry.class);
    }
    /** The classes starting after {@code after} that have live entries (the recount when WAITLIST is switched back on). */
    public List<String> liveClassIds(java.time.Instant after) {
        return mongo.findDistinct(tenantQuery().addCriteria(Criteria.where("state").in(LIVE).and("classStartsAt").gt(after)), "classSessionId", WaitlistEntry.class, String.class);
    }
    /**
     * The live entries the S15 P8 sweep may cancel (R-15-18a; INC-46, E11-T02), by class: those of {@code startedClassIds}
     * (S06's started classes with someone waiting) and those whose own `classStartsAt` is reached (a class that no longer
     * exists). The caller decides with the class's own start: the copy a consumer refreshes after a class moves can be stale
     * either way. Never every live entry of the club: a week ahead's are not read each minute.
     */
    public List<WaitlistEntry> liveStarted(java.util.Collection<String> startedClassIds, java.time.Instant now) {
        var started = Criteria.where("classStartsAt").lte(now);
        var scope = startedClassIds.isEmpty() ? started : new Criteria().orOperator(Criteria.where("classSessionId").in(startedClassIds), started);
        return mongo.find(tenantQuery().addCriteria(Criteria.where("state").in(LIVE)).addCriteria(scope).with(Sort.by("classSessionId", "_id")), WaitlistEntry.class);
    }
    /** R-08-14 (INC-40, E11-T10): the still open FIFO offers (NOTIFIED, `confirmBy > now`) of the given classes. */
    public List<WaitlistEntry> openOffers(Collection<String> classSessionIds, java.time.Instant now) {
        if (classSessionIds.isEmpty()) { return List.of(); }
        return mongo.find(tenantQuery().addCriteria(Criteria.where("classSessionId").in(classSessionIds).and("state").is(WaitlistState.NOTIFIED)
                .and("confirmBy").gt(now)), WaitlistEntry.class);
    }
    /** S15 P6: NOTIFIED (FIFO) entries whose `confirmBy` has passed, oldest offer first. */
    public List<WaitlistEntry> notifiedDue(java.time.Instant now) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("state").is(WaitlistState.NOTIFIED).and("confirmBy").lte(now))
                .with(Sort.by("confirmBy", "_id")), WaitlistEntry.class);
    }
    public WaitlistEntry update(WaitlistEntry next, long expectedVersion) {
        var saved = mongo.findAndReplace(tenantQuery(next.clubId()).addCriteria(Criteria.where("_id").is(next.id()).and("version").is(expectedVersion)),
                next, org.springframework.data.mongodb.core.FindAndReplaceOptions.options().returnNew());
        if (saved == null) { throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.STALE_VERSION); }
        return saved;
    }
    /**
     * R-08-13 (E5-T11, E5-T14): records that the N-15 rows of the offer made at {@code notifiedAt} were written (the
     * consumer calls it after them, in their transaction), only while the entry is still NOTIFIED with that offer. Bumps `version`, so a writer holding an older read fails its compare-and-set
     * instead of dropping the field; inside a transaction a concurrent demotion is a write conflict. False when the
     * offer is gone (taken, left, demoted or superseded).
     */
    public boolean markOfferNotified(String entryId, java.time.Instant notifiedAt) {
        var query = tenantQuery().addCriteria(Criteria.where("_id").is(entryId).and("state").is(WaitlistState.NOTIFIED).and("notifiedAt").is(notifiedAt));
        var update = new org.springframework.data.mongodb.core.query.Update().set("offerNotifiedAt", notifiedAt).inc("version", 1);
        return mongo.updateFirst(query, update, mongo.getCollectionName(WaitlistEntry.class)).getMatchedCount() == 1;
    }
    public List<WaitlistEntry> byIds(Collection<String> ids) {
        if (ids.isEmpty()) { return List.of(); }
        return mongo.find(tenantQuery().addCriteria(Criteria.where("_id").in(ids)), WaitlistEntry.class);
    }
}
