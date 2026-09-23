package com.agilityhub.core.clubs.bookings.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;

@Repository
public class SeatHoldRepository extends TenantRepository<SeatHold> {
    public SeatHoldRepository(MongoTemplate mongo) { super(mongo, SeatHold.class); }
    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        var indexes = mongo.indexOps(SeatHold.class);
        indexes.ensureIndex(new Index().on("expiresAt", ASC).expire(Duration.ZERO).named("seat_hold_ttl"));
        indexes.ensureIndex(new Index().on("clubId", ASC).on("classSessionId", ASC).on("dogId", ASC).unique().named("seat_hold_club_class_dog"));
    }
    /** Live holds of a class: the TTL lags up to 60 s, so `expiresAt > now` is always filtered (R-08-07, T-08-33). */
    public List<SeatHold> live(String classSessionId, Instant now) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("classSessionId").is(classSessionId).and("expiresAt").gt(now)), SeatHold.class);
    }
    /** Creates the dog's hold or refreshes the existing one (same id), R-08-07 step 4. */
    public SeatHold upsert(SeatHold hold) {
        var query = tenantQuery(hold.clubId()).addCriteria(Criteria.where("classSessionId").is(hold.classSessionId()).and("dogId").is(hold.dogId()));
        var update = new Update().setOnInsert("_id", hold.id()).set("memberId", hold.memberId()).set("accountId", hold.accountId())
                .set("waitlistEntryId", hold.waitlistEntryId()).set("createdAt", hold.createdAt()).set("expiresAt", hold.expiresAt());
        return mongo.findAndModify(query, update, FindAndModifyOptions.options().upsert(true).returnNew(true), SeatHold.class);
    }
    public long deleteForClass(String classSessionId) {
        return mongo.remove(tenantQuery().addCriteria(Criteria.where("classSessionId").is(classSessionId)), SeatHold.class).getDeletedCount();
    }
}
