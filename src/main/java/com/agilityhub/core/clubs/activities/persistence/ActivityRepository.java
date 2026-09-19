package com.agilityhub.core.clubs.activities.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.*;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;

@Repository
public class ActivityRepository extends TenantRepository<Activity> {
    public ActivityRepository(MongoTemplate mongo) { super(mongo, Activity.class); }
    public java.util.Optional<Activity> findBySlug(String slug) {
        return java.util.Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("slug").is(slug)), Activity.class));
    }
    public Activity require(String id) { return findById(id).orElseThrow(() -> new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.NOT_FOUND)); }
    public Activity update(Activity next, long expected) {
        org.bson.Document data = new org.bson.Document(); mongo.getConverter().write(next, data);
        Update update = new Update();
        data.forEach((key, value) -> { if (!java.util.Set.of("_id", "_class", "clubId").contains(key)) update.set(key,value); });
        for (var field : Activity.class.getRecordComponents()) if (!data.containsKey(field.getName()) && !java.util.Set.of("id", "clubId").contains(field.getName())) update.unset(field.getName());
        if (mongo.updateFirst(tenantQuery(next.clubId()).addCriteria(Criteria.where("_id").is(next.id()).and("version").is(expected)),update,org.bson.Document.class,"activities").getMatchedCount()!=1)
            throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.STALE_VERSION);
        return next;
    }
    public java.util.List<String> placementIds(String id) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("activityId").is(id)),org.bson.Document.class,"placements").stream().map(d -> d.getString("_id")).toList();
    }
    public Activity lock(String id) {
        var result=mongo.findAndModify(tenantQuery().addCriteria(Criteria.where("_id").is(id)),new Update().inc("registrationSeq",1).inc("version",0),
                org.springframework.data.mongodb.core.FindAndModifyOptions.options().returnNew(true),Activity.class);
        if(result==null) throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.NOT_FOUND);
        return result;
    }
    public java.util.List<Activity> findPublished() {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("state").is("PUBLISHED")),Activity.class);
    }
    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        mongo.indexOps(Activity.class).ensureIndex(new Index().on("clubId", ASC).on("slug", ASC).unique().named("activity_club_slug"));
        mongo.indexOps(Activity.class).ensureIndex(new Index().on("clubId", ASC).on("state", ASC).on("startsAt", ASC).named("activity_club_state_start"));
        mongo.indexOps(Activity.class).ensureIndex(new Index().on("clubId", ASC).on("date", ASC).named("activity_club_date"));
    }
}
