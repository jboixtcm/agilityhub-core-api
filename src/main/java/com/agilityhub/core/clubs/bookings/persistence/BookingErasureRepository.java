package com.agilityhub.core.clubs.bookings.persistence;

import com.agilityhub.core.shared.application.TenantContext;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** S14 R-14-14/R-14-15 reads and writes of this context in the current club (see {@code BookingsErasure}). */
@Repository
public class BookingErasureRepository {
    private final MongoTemplate mongo;
    public BookingErasureRepository(MongoTemplate mongo) { this.mongo = mongo; }
    private static Criteria club() { return Criteria.where("clubId").is(TenantContext.require()); }

    public boolean futureBookings(Collection<String> memberIds, Instant now) {
        return mongo.exists(Query.query(club().and("memberId").in(memberIds).and("state").in("ACTIVE", "PAYMENT_PENDING")
                .and("classStartsAt").gt(now)), "bookings");
    }
    /**
     * The changed documents per collection. The club's text goes from the members' own bookings; their name as booker or canceller
     * goes from every booking of the club, another member's too (E11-T16: a family-group member books for another's dog, an
     * instructor-member cancels for someone else).
     */
    public Map<String, Long> erase(Collection<String> memberIds, Collection<String> accountIds) {
        var mine = Query.query(club().and("memberId").in(memberIds));
        long changed = mongo.updateMulti(mine, new Update().unset("cancelMessage"), "bookings").getModifiedCount();
        changed += mongo.updateMulti(Query.query(club().orOperator(Criteria.where("bookedBy.accountId").in(accountIds),
                Criteria.where("bookedBy.impersonatedMemberId").in(memberIds))), new Update().unset("bookedBy.displayName"), "bookings").getModifiedCount();
        changed += mongo.updateMulti(Query.query(club().orOperator(Criteria.where("cancelledBy.accountId").in(accountIds),
                Criteria.where("cancelledBy.impersonatedMemberId").in(memberIds))), new Update().unset("cancelledBy.displayName"), "bookings").getModifiedCount();
        long holds = mongo.remove(Query.query(club().and("memberId").in(memberIds)), "seat_holds").getDeletedCount();
        // An erased account that marked attendance (a helper or instructor who was also a member) loses its name as marker.
        long marks = accountIds.isEmpty() ? 0 : mongo.updateMulti(Query.query(club().and("markedBy.accountId").in(accountIds).and("markedBy.displayName").exists(true)),
                new Update().unset("markedBy.displayName"), "attendances").getModifiedCount();
        return Map.of("bookings", changed, "seat_holds", holds, "attendances", marks);
    }

    /** S14 R-14-13: the member's rows of a collection of the current club, raw. */
    public java.util.List<org.bson.Document> rows(String collection, String memberId) {
        return mongo.find(Query.query(club().and("memberId").is(memberId)).with(org.springframework.data.domain.Sort.by("_id")), org.bson.Document.class, collection);
    }
}
