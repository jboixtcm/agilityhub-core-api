package com.agilityhub.core.clubs.training.persistence;

import com.agilityhub.core.shared.application.TenantContext;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** S14 R-14-14/R-14-15 reads and writes of this context in the current club (see {@code TrainingErasure}). */
@Repository
public class TrainingErasureRepository {
    private final MongoTemplate mongo;
    public TrainingErasureRepository(MongoTemplate mongo) { this.mongo = mongo; }
    private static Criteria club() { return Criteria.where("clubId").is(TenantContext.require()); }

    public boolean futureBookings(Collection<String> memberIds, Instant now) {
        return mongo.exists(Query.query(club().and("memberId").in(memberIds).and("state").is("ACTIVE").and("startsAt").gt(now)), "training_bookings");
    }
    /** The changed documents per collection. */
    public Map<String, Long> erase(Collection<String> memberIds, Collection<String> accountIds) {
        long changed = mongo.updateMulti(Query.query(club().and("memberId").in(memberIds)), new Update().unset("cancelNote"), "training_bookings")
                .getModifiedCount();
        return Map.of("training_bookings", changed);
    }

    /** S14 R-14-13: the member's training bookings of the current club, raw. */
    public java.util.List<org.bson.Document> rows(String memberId) {
        return mongo.find(Query.query(club().and("memberId").is(memberId)).with(org.springframework.data.domain.Sort.by("_id")), org.bson.Document.class, "training_bookings");
    }
}
