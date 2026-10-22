package com.agilityhub.core.platform.persistence.privacy;

import com.agilityhub.core.shared.application.TenantContext;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/**
 * S14 R-14-15 and S15 R-15-19, the technical traces of an erased member: the processed outbox records about it (their payloads
 * carry names, e-mails and diffs; S15 deletes the published ones after `jobs.retention.domainEventsDays` anyway, and never
 * touches an unprocessed one), the idempotency replays of an erased account and its security events (R-14-15 «esborrats
 * físicament»). E11-T16: «processed» is `PUBLISHED` or `FAILED` (a record the dispatcher gave up on keeps its payload for ever,
 * and has no consumer left to serve once its subject is erased), and the run traces lose the dog names P3 lists
 * (`job_runs.items[].detail[attendances].value[].dogName`, R-15-13).
 */
@Repository
public class PrivacyTraceRepository {
    static final List<String> PROCESSED = List.of("PUBLISHED", "FAILED");
    /** The key of P3's detail entry whose value lists `{attendanceId, bookingId, memberId, dogName, classDate}` (S15 R-15-13). */
    static final String ATTENDANCES = "attendances";
    private final MongoTemplate mongo;
    public PrivacyTraceRepository(MongoTemplate mongo) { this.mongo = mongo; }
    private static Criteria club() { return Criteria.where("clubId").is(TenantContext.require()); }
    private static Criteria about(Collection<String> memberIds, Collection<String> dogIds) {
        return new Criteria().orOperator(Criteria.where("aggregateId").in(memberIds), Criteria.where("aggregateId").in(dogIds),
                Criteria.where("payload.memberId").in(memberIds), Criteria.where("payload.dogId").in(dogIds));
    }

    public long deleteProcessedEvents(Collection<String> memberIds, Collection<String> dogIds) {
        return mongo.remove(Query.query(club().and("status").in(PROCESSED)).addCriteria(about(memberIds, dogIds)), "domain_events").getDeletedCount();
    }
    /** The processed records of one event type about these aggregates of the club (an erased instructor's `InstructorChanged`). */
    public long deleteProcessedEvents(String type, Collection<String> aggregateIds) {
        return mongo.remove(processed(type, aggregateIds), "domain_events").getDeletedCount();
    }
    /** E11-T16 round 2: which of these aggregates of the club still have a processed record of this type. */
    public Set<String> withProcessedEvents(String type, Collection<String> aggregateIds) {
        if (aggregateIds.isEmpty()) { return Set.of(); }
        return new TreeSet<>(mongo.findDistinct(processed(type, aggregateIds), "aggregateId", "domain_events", String.class));
    }
    private static Query processed(String type, Collection<String> aggregateIds) {
        return Query.query(club().and("status").in(PROCESSED).and("type").is(type).and("aggregateId").in(aggregateIds));
    }

    /** The club's run traces that list one of these members with a dog name. */
    private static Criteria namingDogs(Collection<String> memberIds) {
        return Criteria.where("items.detail").elemMatch(Criteria.where("key").is(ATTENDANCES)
                .and("value").elemMatch(Criteria.where("memberId").in(memberIds).and("dogName").exists(true)));
    }
    /** E11-T16: the members' rows of the club's run traces keep their ids and lose the dog's name; the changed traces. */
    public long forgetDogNames(Collection<String> memberIds) {
        if (memberIds.isEmpty()) { return 0; }
        var update = new Update().unset("items.$[item].detail.$[entry].value.$[row].dogName")
                .filterArray(Criteria.where("item.detail.key").is(ATTENDANCES)).filterArray(Criteria.where("entry.key").is(ATTENDANCES))
                .filterArray(Criteria.where("row.memberId").in(memberIds));
        return mongo.updateMulti(Query.query(club()).addCriteria(namingDogs(memberIds)), update, "job_runs").getModifiedCount();
    }

    /**
     * E11-T16 (S15 R-15-19's monthly sweep): which of these members of the club still have a processed record about them or one of
     * their dogs ({@code ownerOfDog}: dog → member), or a run trace that names one of their dogs.
     */
    public Set<String> traced(Collection<String> memberIds, Map<String, String> ownerOfDog) {
        var traced = new TreeSet<String>();
        if (memberIds.isEmpty()) { return traced; }
        var members = Set.copyOf(memberIds);
        var events = Query.query(club().and("status").in(PROCESSED)).addCriteria(about(memberIds, ownerOfDog.keySet()));
        for (String field : List.of("aggregateId", "payload.memberId", "payload.dogId")) {
            for (String id : mongo.findDistinct(events, field, "domain_events", String.class)) {
                // Round 2: a record may carry an explicit null id (a reminder without a member, a training event without a dog).
                if (id == null) { continue; }
                if (members.contains(id)) { traced.add(id); }
                else if (ownerOfDog.containsKey(id)) { traced.add(ownerOfDog.get(id)); }
            }
        }
        var rows = Aggregation.newAggregation(Aggregation.match(club().andOperator(namingDogs(memberIds))), Aggregation.unwind("items"),
                Aggregation.unwind("items.detail"), Aggregation.match(Criteria.where("items.detail.key").is(ATTENDANCES)), Aggregation.unwind("items.detail.value"),
                Aggregation.match(Criteria.where("items.detail.value.memberId").in(memberIds).and("items.detail.value.dogName").exists(true)),
                Aggregation.group("items.detail.value.memberId"));
        mongo.aggregate(rows, "job_runs", Document.class).forEach(row -> traced.add(row.getString("_id")));
        return traced;
    }

    public long deleteReplays(String accountId) {
        return mongo.remove(Query.query(Criteria.where("accountId").is(accountId)), "idempotency_records").getDeletedCount();
    }
    public long deleteSecurityEvents(String accountId) {
        return mongo.remove(Query.query(Criteria.where("accountId").is(accountId)), "security_events").getDeletedCount();
    }
}
