package com.agilityhub.core.clubs.scheduling.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.*;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;

@Repository
public class WeekRepository extends TenantRepository<Week> {
    public WeekRepository(MongoTemplate mongo) { super(mongo, Week.class); }
    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        mongo.indexOps(Week.class).ensureIndex(new Index().on("clubId", ASC).on("startDate", ASC).unique().named("week_club_start"));
    }

    public Week update(Week next, long expectedVersion) {
        var query = tenantQuery(next.clubId()).addCriteria(Criteria.where("_id").is(next.id()).and("version").is(expectedVersion));
        var result = mongo.findAndReplace(query, next, org.springframework.data.mongodb.core.FindAndReplaceOptions.options().returnNew());
        if (result == null) { throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.STALE_VERSION); }
        return result;
    }
    public java.util.Optional<Week> forStart(java.time.LocalDate start) {
        return java.util.Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("startDate").is(start)), Week.class));
    }

    public Week getOrCreate(Week proposed) {
        var document = new org.bson.Document(); mongo.getConverter().write(proposed, document);
        // UpdateMapper applies property converters; supply the domain dates exactly once.
        document.put("startDate", proposed.startDate()); document.put("endDate", proposed.endDate());
        var update = new Update(); document.forEach(update::setOnInsert);
        return mongo.findAndModify(tenantQuery(proposed.clubId()).addCriteria(Criteria.where("startDate").is(proposed.startDate())),
                update, org.springframework.data.mongodb.core.FindAndModifyOptions.options().upsert(true).returnNew(true), Week.class);
    }
}
