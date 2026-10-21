package com.agilityhub.core.clubs.activities.persistence;

import com.agilityhub.core.shared.application.TenantContext;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** S14 R-14-14/R-14-15 reads and writes of this context in the current club (see {@code ActivitiesErasure}). */
@Repository
public class ActivityErasureRepository {
    private final MongoTemplate mongo;
    public ActivityErasureRepository(MongoTemplate mongo) { this.mongo = mongo; }
    private static Criteria club() { return Criteria.where("clubId").is(TenantContext.require()); }

    public boolean futureBookings(Collection<String> memberIds, Instant now) {
        return mongo.exists(Query.query(club().and("memberId").in(memberIds).and("state").in("ACTIVE", "WAITLISTED")
                .and("activityStartsAt").gt(now)), "activity_registrations");
    }
    /** The changed documents per collection: the members' name as registrant of any registration of the club, another member's too (E11-T16). */
    public Map<String, Long> erase(Collection<String> memberIds, Collection<String> accountIds) {
        long changed = mongo.updateMulti(Query.query(club().orOperator(
                Criteria.where("registeredBy.accountId").in(accountIds), Criteria.where("registeredBy.impersonatedMemberId").in(memberIds))),
                new Update().unset("registeredBy.displayName"), "activity_registrations").getModifiedCount();
        return Map.of("activity_registrations", changed);
    }
}
