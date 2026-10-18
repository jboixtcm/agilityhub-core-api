package com.agilityhub.core.clubs.scheduling.persistence;

import com.agilityhub.core.shared.application.TenantContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** S14 R-14-15 writes of this context in the current club (see {@code SchedulingErasure}). */
@Repository
public class SchedulingErasureRepository {
    private final MongoTemplate mongo;
    public SchedulingErasureRepository(MongoTemplate mongo) { this.mongo = mongo; }

    /** The attendance summaries the account saved name it no more: `savedByName` becomes `label`. Returns the changed classes. */
    public long renameSavedBy(String accountId, String label) {
        return mongo.updateMulti(Query.query(Criteria.where("clubId").is(TenantContext.require()).and("attendanceSummary.savedByAccountId").is(accountId)
                        .and("attendanceSummary.savedByName").ne(label)),
                new Update().set("attendanceSummary.savedByName", label), "class_sessions").getModifiedCount();
    }
}
