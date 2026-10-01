package com.agilityhub.core.platform.persistence;

import com.agilityhub.core.shared.persistence.GlobalRepository;
import java.util.Optional;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
public class ClubRepository extends GlobalRepository<Club> {
    public ClubRepository(MongoTemplate mongo) { super(mongo, Club.class); }
    public Optional<Club> findByHost(String host) {
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("domains").elemMatch(
                Criteria.where("host").is(host).and("status").is(Club.DomainStatus.VERIFIED))), Club.class));
    }
    public Optional<Club> findByAnyHost(String host) {
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("domains.host").is(host)), Club.class));
    }
    public Optional<Club> findBySlug(String slug) {
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("slug").is(slug)), Club.class));
    }
    /** S15 R-15-01/R-15-03: ACTIVE and ONBOARDING clubs run processes; SUSPENDED ones record SKIPPED{CLUB_INACTIVE}. */
    public java.util.List<Club> schedulableClubs() {
        return mongo.find(Query.query(Criteria.where("status").in(Club.Status.ACTIVE, Club.Status.ONBOARDING, Club.Status.SUSPENDED)
                .and("template").ne(true)).with(org.springframework.data.domain.Sort.by("_id")), Club.class);
    }
    public java.util.List<Club> activeClubs() { return mongo.find(Query.query(Criteria.where("status").is(Club.Status.ACTIVE).and("template").ne(true)),Club.class); }
    public int nextMemberNumber(int minimum) {
        var query=Query.query(Criteria.where("_id").is(com.agilityhub.core.shared.application.TenantContext.require()));
        var club=mongo.findOne(query,org.bson.Document.class,"clubs");
        if(club==null) throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.NOT_FOUND);
        long reserved=((Number)club.getOrDefault("nextMemberNumber",1L)).longValue();
        return new MemberNumberSequenceRepository(mongo).next(Math.max(reserved,minimum));
    }
    public void reserveMemberNumbers(int maximum) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(com.agilityhub.core.shared.application.TenantContext.require())),
                new org.springframework.data.mongodb.core.query.Update().max("nextMemberNumber", (long) maximum + 1), Club.class);
    }
    /**
     * E8-T02 (R-12-08): the next {@code count} numbers of the counter {@code key} of `billing.counters`, taken atomically in
     * the caller's transaction; returns the first one. A number is never reused (round 2, ruling E87):
     * <ul>
     * <li>a key without a counter yet — a new year's series with a yearly reset, or the other key after
     * `billing.invoiceResetYearly` was toggled — starts at the highest counter of the stored keys {@code sameSeries} accepts
     * (they number the same series), or at the imported `billing.nextNumber` while the club has no counter at all, else 1;</li>
     * <li>no counter ever hands out a number below {@code floor}: the caller's «after the highest number its series has
     * issued», so two keys of one series never meet.</li>
     * </ul>
     * The club's version is bumped, so a full club save read before this write fails instead of writing the old counter back.
     */
    public long reserveInvoiceNumbers(String clubId, String key, int count, long floor, java.util.function.Predicate<String> sameSeries) {
        if (count < 1) { throw new IllegalArgumentException("count"); }
        String field = counterField(key), stored = counterKey(key);
        var id = Query.query(Criteria.where("_id").is(clubId));
        var club = mongo.findOne(id, org.bson.Document.class, "clubs");
        if (club == null) { throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.NOT_FOUND); }
        var billing = club.get("billing", org.bson.Document.class);
        var counters = billing == null ? null : billing.get("counters", org.bson.Document.class);
        if (counters == null || !counters.containsKey(stored)) {
            long seed = (counters == null || counters.isEmpty()) && billing != null && billing.get("nextNumber") instanceof Number imported ? imported.longValue() : 1L;
            if (counters != null) {
                for (var other : counters.entrySet()) {
                    if (other.getValue() instanceof Number next && sameSeries.test(other.getKey())) { seed = Math.max(seed, next.longValue()); }
                }
            }
            mongo.updateFirst(Query.query(Criteria.where("_id").is(clubId).and(field).exists(false)),
                    new org.springframework.data.mongodb.core.query.Update().set(field, seed), "clubs");
        }
        mongo.updateFirst(id, new org.springframework.data.mongodb.core.query.Update().max(field, floor), "clubs");
        var before = mongo.findAndModify(id, new org.springframework.data.mongodb.core.query.Update().inc(field, (long) count).inc("version", 1L),
                org.springframework.data.mongodb.core.FindAndModifyOptions.options().returnNew(false), org.bson.Document.class, "clubs");
        return ((Number) before.get("billing", org.bson.Document.class).get("counters", org.bson.Document.class).get(stored)).longValue();
    }
    /** The next number of the counter {@code key}, empty while it has none. */
    public Optional<Long> invoiceCounter(String clubId, String key) {
        var club = mongo.findOne(Query.query(Criteria.where("_id").is(clubId)), org.bson.Document.class, "clubs");
        var billing = club == null ? null : club.get("billing", org.bson.Document.class);
        var counters = billing == null ? null : billing.get("counters", org.bson.Document.class);
        return counters == null || !(counters.get(counterKey(key)) instanceof Number next) ? Optional.empty() : Optional.of(next.longValue());
    }
    /**
     * R-12-14: gives a block of numbers back — the counter returns to {@code restoreTo} only while it still is {@code expectedNext}
     * (nothing was numbered after the block); returns whether it did.
     */
    public boolean restoreInvoiceCounter(String clubId, String key, long expectedNext, long restoreTo) {
        String field = counterField(key);
        return mongo.updateFirst(Query.query(Criteria.where("_id").is(clubId).and(field).is(expectedNext)),
                new org.springframework.data.mongodb.core.query.Update().set(field, restoreTo).inc("version", 1L), "clubs").getModifiedCount() == 1;
    }
    /** A series is user text (`billing.invoiceSeriesPattern`): `.` and `$` would split the field path. */
    public static String counterKey(String key) { return key.replace('.', '_').replace('$', '_'); }
    private static String counterField(String key) { return "billing.counters." + counterKey(key); }
    public void ensureIndexes() {
        mongo.indexOps(Club.class).ensureIndex(new Index().on("slug", Direction.ASC).unique().named("club_slug"));
        mongo.indexOps(Club.class).ensureIndex(new Index().on("domains.host", Direction.ASC).unique().sparse().named("club_host"));
    }
}
