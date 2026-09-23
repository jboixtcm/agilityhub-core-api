package com.agilityhub.core.clubs.scheduling.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.*;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;

@Repository
public class ClassSessionRepository extends TenantRepository<ClassSession> {
    public ClassSessionRepository(MongoTemplate mongo) { super(mongo, ClassSession.class); }
    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        mongo.indexOps(ClassSession.class).ensureIndex(new Index().on("clubId", ASC).on("date", ASC).on("state", ASC).named("class_club_date_state"));
        mongo.indexOps(ClassSession.class).ensureIndex(new Index().on("clubId", ASC).on("startsAt", ASC).named("class_club_start"));
        mongo.indexOps(ClassSession.class).ensureIndex(new Index().on("clubId", ASC).on("weekId", ASC).named("class_club_week"));
        mongo.indexOps(ClassSession.class).ensureIndex(new Index().on("clubId", ASC).on("ringId", ASC).on("startsAt", ASC).named("class_club_ring_start"));
        mongo.indexOps(ClassSession.class).ensureIndex(new Index().on("clubId", ASC).on("state", ASC).on("endsAt", ASC).named("class_club_state_end"));
    }
    public boolean hasClasses() { return mongo.exists(tenantQuery(), ClassSession.class); }
    public java.util.List<ClassSession> forWeek(String weekId) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("weekId").is(weekId)), ClassSession.class);
    }

    public ClassSession update(ClassSession next, long version) {
        var saved = mongo.findAndReplace(tenantQuery(next.clubId()).addCriteria(Criteria.where("_id").is(next.id()).and("version").is(version)),
                next, org.springframework.data.mongodb.core.FindAndReplaceOptions.options().returnNew());
        if (saved == null) { throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.STALE_VERSION); }
        return saved;
    }
    public java.util.List<ClassSession> between(java.time.Instant from, java.time.Instant to) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("startsAt").lt(to).and("endsAt").gt(from)), ClassSession.class);
    }
    public java.util.List<ClassSession> findActiveBetween(String clubId, java.time.Instant from, java.time.Instant to) {
        return mongo.find(tenantQuery(clubId).addCriteria(Criteria.where("state").is("ACTIVE").and("startsAt").gte(from).lt(to)), ClassSession.class);
    }
    public java.util.Optional<ClassSession> findLive(java.time.LocalDate date, String startTime, String ringId) {
        return java.util.Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("date").is(date).and("startTime").is(startTime)
                .and("ringId").is(ringId).and("state").in("DRAFT", "ACTIVE")), ClassSession.class));
    }
    public long countFutureByRing(String ringId, java.time.Instant now) { return future("ringId", ringId, now); }
    public long countFutureByInstructor(String instructorId, java.time.Instant now) { return future("instructorIds", instructorId, now); }
    private long future(String field, String id, java.time.Instant now) {
        return mongo.count(tenantQuery().addCriteria(Criteria.where(field).is(id).and("state").in("DRAFT", "ACTIVE").and("startsAt").gt(now)), ClassSession.class);
    }
    public void finishedAt(String id, java.time.Instant now) {
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id)), new Update().set("finishedAt", now), ClassSession.class);
    }
}
