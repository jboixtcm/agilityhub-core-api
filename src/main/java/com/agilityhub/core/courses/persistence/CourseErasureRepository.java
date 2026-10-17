package com.agilityhub.core.courses.persistence;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** S14 R-14-15 / S16 §7: an erased account's own courses (`ownerType = ACCOUNT`) become `PRIVATE` without `designerName`. */
@Repository
public class CourseErasureRepository {
    private final MongoTemplate mongo;
    public CourseErasureRepository(MongoTemplate mongo) { this.mongo = mongo; }
    public long eraseOwnedBy(String accountId) {
        return mongo.updateMulti(Query.query(Criteria.where("ownerType").is("ACCOUNT").and("ownerAccountId").is(accountId)),
                new Update().set("visibility", "PRIVATE").unset("designerName"), Course.class).getModifiedCount();
    }
}
