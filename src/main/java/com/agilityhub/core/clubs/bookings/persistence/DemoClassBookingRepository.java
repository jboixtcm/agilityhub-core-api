package com.agilityhub.core.clubs.bookings.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import java.util.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;

@Repository
public class DemoClassBookingRepository extends TenantRepository<DemoClassBooking> {
    public DemoClassBookingRepository(MongoTemplate mongo) { super(mongo, DemoClassBooking.class); }
    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        mongo.indexOps(DemoClassBooking.class).ensureIndex(new Index().on("clubId", ASC).on("classId", ASC).on("state", ASC).named("demo_booking_club_class_state"));
    }
    public List<DemoClassBooking> forClass(String classId, DemoClassBooking.State state) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("classId").is(classId).and("state").is(state)), DemoClassBooking.class).stream()
                .sorted(Comparator.comparing((DemoClassBooking b) -> b.position() == null ? 0 : b.position()).thenComparing(DemoClassBooking::id)).toList();
    }
    public List<DemoClassBooking> byIds(Collection<String> ids) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("_id").in(ids)), DemoClassBooking.class);
    }
    public DemoClassBooking update(DemoClassBooking next, long expectedVersion) {
        var saved = mongo.findAndReplace(tenantQuery(next.clubId()).addCriteria(Criteria.where("_id").is(next.id()).and("version").is(expectedVersion)),
                next, org.springframework.data.mongodb.core.FindAndReplaceOptions.options().returnNew());
        if (saved == null) { throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.STALE_VERSION); }
        return saved;
    }
}
