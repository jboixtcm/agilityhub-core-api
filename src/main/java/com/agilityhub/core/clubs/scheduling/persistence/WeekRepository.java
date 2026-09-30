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
    /**
     * S06 T-06-23 (E5-T28, A4-01): a class written into a week that is not VALIDATED writes the week too (`version + 1`), so it
     * conflicts with a concurrent validation of that week and one of them runs again: the validation then sees the new
     * DRAFT, or the class sees the VALIDATED week and is born ACTIVE. It is a real write: an identical replacement is a
     * no-op for MongoDB and conflicts with nothing.
     */
    public void touch(Week week) {
        var query = tenantQuery(week.clubId()).addCriteria(Criteria.where("_id").is(week.id()).and("version").is(week.version()));
        if (mongo.updateFirst(query, new Update().inc("version", 1L), Week.class).getModifiedCount() != 1) {
            throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.STALE_VERSION);
        }
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
