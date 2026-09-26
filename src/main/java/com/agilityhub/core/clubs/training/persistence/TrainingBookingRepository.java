package com.agilityhub.core.clubs.training.persistence;

import com.agilityhub.core.clubs.training.domain.TrainingBookingState;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndReplaceOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;

@Repository
public class TrainingBookingRepository extends TenantRepository<TrainingBooking> {
    public TrainingBookingRepository(MongoTemplate mongo) { super(mongo, TrainingBooking.class); }
    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        var indexes = mongo.indexOps(TrainingBooking.class);
        // R-09-06: the final guard against double booking of a seat, even from a stale grid.
        indexes.ensureIndex(new Index().on("clubId", ASC).on("ringId", ASC).on("startsAt", ASC).on("seatIndex", ASC).unique()
                .partial(PartialIndexFilter.of(Criteria.where("state").is("ACTIVE"))).named("training_active_seat"));
        indexes.ensureIndex(new Index().on("clubId", ASC).on("startsAt", ASC).named("training_club_starts"));
        indexes.ensureIndex(new Index().on("clubId", ASC).on("dogId", ASC).on("weekStart", ASC).on("state", ASC).named("training_club_dog_week_state"));
        indexes.ensureIndex(new Index().on("clubId", ASC).on("memberId", ASC).on("weekStart", ASC).on("state", ASC).named("training_club_member_week_state"));
        indexes.ensureIndex(new Index().on("clubId", ASC).on("state", ASC).on("startsAt", ASC).named("training_club_state_starts"));
    }
    public TrainingBooking require(String id) { return findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)); }
    private static Criteria active() { return Criteria.where("state").is(TrainingBookingState.ACTIVE); }
    /** ACTIVE bookings overlapping `[from, to)`, optionally of one ring (R-09-03 step 3, R-09-12, R-09-13). */
    public List<TrainingBooking> activeBetween(Instant from, Instant to, String ringId) {
        var criteria = active().and("startsAt").lt(to).and("endsAt").gt(from);
        if (ringId != null) { criteria = criteria.and("ringId").is(ringId); }
        return mongo.find(tenantQuery().addCriteria(criteria).with(Sort.by("startsAt", "ringId", "seatIndex")), TrainingBooking.class);
    }
    public List<TrainingBooking> activeForDog(String dogId, Instant from, Instant to) {
        return mongo.find(tenantQuery().addCriteria(active().and("dogId").is(dogId).and("startsAt").lt(to).and("endsAt").gt(from)), TrainingBooking.class);
    }
    /** R-09-05: every booking of a training week for the unit (`dogId` or `memberId`), any state (the domain counts). */
    public List<TrainingBooking> week(String field, String value, Instant weekStart) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where(field).is(value).and("weekStart").is(weekStart)).with(Sort.by("startsAt", "_id")), TrainingBooking.class);
    }
    /** R-09-14 system paths: ACTIVE bookings of a member (or dog) starting after an instant. */
    public List<TrainingBooking> activeAfter(String field, String value, Instant after) {
        return mongo.find(tenantQuery().addCriteria(active().and(field).is(value).and("startsAt").gt(after)).with(Sort.by("startsAt", "_id")), TrainingBooking.class);
    }
    /** `/me/training-bookings`: bookings made by the given members or for the given dogs, optionally filtered. */
    public List<TrainingBooking> visible(Collection<String> memberIds, Collection<String> dogIds, TrainingBookingState state, Instant from, Instant to) {
        var criteria = new Criteria().orOperator(Criteria.where("memberId").in(memberIds), Criteria.where("dogId").in(dogIds));
        var filters = new ArrayList<Criteria>(); filters.add(criteria);
        if (state != null) { filters.add(Criteria.where("state").is(state)); }
        if (from != null) { filters.add(Criteria.where("startsAt").gte(from)); }
        if (to != null) { filters.add(Criteria.where("startsAt").lt(to)); }
        return mongo.find(tenantQuery().addCriteria(new Criteria().andOperator(filters.toArray(Criteria[]::new))).with(Sort.by("startsAt", "_id")), TrainingBooking.class);
    }
    /** S10 R-10-14 (25): every booking of the given dogs starting at or after {@code from}, any state, by start. */
    public List<TrainingBooking> forDogsSince(Collection<String> dogIds, Instant from) {
        if (dogIds.isEmpty()) { return List.of(); }
        return mongo.find(tenantQuery().addCriteria(Criteria.where("dogId").in(dogIds).and("startsAt").gte(from)).with(Sort.by("startsAt", "_id")), TrainingBooking.class);
    }
    /** S10 R-10-08: ACTIVE bookings of a dog with `endsAt ∈ [from, to)` (the trainings done in the 30-day window). */
    public long countActiveEndingBetween(String dogId, Instant from, Instant to) {
        return mongo.count(tenantQuery().addCriteria(active().and("dogId").is(dogId).and("endsAt").gte(from).lt(to)), TrainingBooking.class);
    }
    /** Any state, `startsAt ∈ [from, until)` (the dashboard KPI of E3-T04). */
    public List<TrainingBooking> startingBetween(String clubId, Instant from, Instant until) {
        return mongo.find(tenantQuery(clubId).addCriteria(Criteria.where("startsAt").gte(from).lt(until)), TrainingBooking.class);
    }
    public Optional<TrainingBooking> byIdempotencyKey(String accountId, String key) {
        return Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("createdByAccountId").is(accountId).and("idempotencyKey").is(key)), TrainingBooking.class));
    }
    /** Compare-and-set on `version`. */
    public TrainingBooking update(TrainingBooking next, long expectedVersion) {
        var saved = mongo.findAndReplace(tenantQuery(next.clubId()).addCriteria(Criteria.where("_id").is(next.id()).and("version").is(expectedVersion)),
                next, FindAndReplaceOptions.options().returnNew());
        if (saved == null) { throw new ApiException(ErrorCode.STALE_VERSION); }
        return saved;
    }
}
