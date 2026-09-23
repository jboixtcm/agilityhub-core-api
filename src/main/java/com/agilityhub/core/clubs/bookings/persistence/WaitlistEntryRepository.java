package com.agilityhub.core.clubs.bookings.persistence;

import com.agilityhub.core.clubs.bookings.domain.WaitlistState;
import com.agilityhub.core.shared.persistence.TenantRepository;
import java.util.*;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;

@Repository
public class WaitlistEntryRepository extends TenantRepository<WaitlistEntry> {
    public static final List<WaitlistState> LIVE = List.of(WaitlistState.ACTIVE, WaitlistState.NOTIFIED);
    public WaitlistEntryRepository(MongoTemplate mongo) { super(mongo, WaitlistEntry.class); }
    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        var indexes = mongo.indexOps(WaitlistEntry.class);
        indexes.ensureIndex(new Index().on("clubId", ASC).on("classSessionId", ASC).on("state", ASC).named("waitlist_club_class_state"));
        indexes.ensureIndex(new Index().on("clubId", ASC).on("dogId", ASC).on("bookingWeekKey", ASC).on("state", ASC).named("waitlist_club_dog_week_state"));
        // S15 P6 expires NOTIFIED entries whose confirmBy has passed.
        indexes.ensureIndex(new Index().on("clubId", ASC).on("state", ASC).on("confirmBy", ASC).named("waitlist_club_state_confirm"));
    }
    /** Live (ACTIVE or NOTIFIED) entries of a class in FIFO order. */
    public List<WaitlistEntry> live(String classSessionId) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("classSessionId").is(classSessionId).and("state").in(LIVE))
                .with(Sort.by("position", "_id")), WaitlistEntry.class);
    }
    public WaitlistEntry update(WaitlistEntry next, long expectedVersion) {
        var saved = mongo.findAndReplace(tenantQuery(next.clubId()).addCriteria(Criteria.where("_id").is(next.id()).and("version").is(expectedVersion)),
                next, org.springframework.data.mongodb.core.FindAndReplaceOptions.options().returnNew());
        if (saved == null) { throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.STALE_VERSION); }
        return saved;
    }
    public List<WaitlistEntry> byIds(Collection<String> ids) {
        if (ids.isEmpty()) { return List.of(); }
        return mongo.find(tenantQuery().addCriteria(Criteria.where("_id").in(ids)), WaitlistEntry.class);
    }
}
