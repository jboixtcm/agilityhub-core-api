package com.agilityhub.core.clubs.catalogs.persistence;

import com.agilityhub.core.clubs.catalogs.application.UsageCounter;
import com.agilityhub.core.clubs.catalogs.domain.CatalogKind;
import com.agilityhub.core.shared.domain.TenantEntity;
import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.Clock;
import java.util.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Repository;

/** Read projections over references, including collections not populated until the owning vertical ships. */
@Repository
public class MongoUsageCounter extends TenantRepository<MongoUsageCounter.Reference> implements UsageCounter {
    public record Reference(String id, String clubId) implements TenantEntity { }
    private final Clock clock;
    public MongoUsageCounter(MongoTemplate mongo, Clock clock) { super(mongo, Reference.class); this.clock = clock; }
    private long count(String collection, Criteria criteria) {
        return mongo.count(tenantQuery().addCriteria(criteria), collection);
    }
    private Criteria future(String field, String id) {
        return Criteria.where(field).is(id).and("startsAt").gt(clock.instant()).and("status").ne("CANCELLED");
    }
    @Override public Map<String, Long> usage(CatalogKind kind, String id) {
        return switch (kind) {
            case LEVEL -> Map.of("activeDogs", count("dogs", Criteria.where("levelId").is(id).and("status").is("ACTIVE")),
                    "futureClassSessions", count("class_sessions", future("levelIds", id)),
                    "templateClasses", count("template_classes", Criteria.where("levelIds").is(id)));
            case RING -> Map.of("futureClassSessions", count("class_sessions", future("ringId", id)),
                    "futureTrainingBookings", count("training_bookings", future("ringId", id)),
                    "templateClasses", count("template_classes", Criteria.where("ringId").is(id)),
                    "ringBlocks", count("ring_blocks", Criteria.where("ringId").is(id)));
            case FAQ -> Map.of();
        };
    }
    @Override public boolean hasReferences(CatalogKind kind, String id) {
        return switch (kind) {
            case LEVEL -> count("dogs", Criteria.where("levelId").is(id))
                    + count("class_sessions", Criteria.where("levelIds").is(id))
                    + count("template_classes", Criteria.where("levelIds").is(id)) > 0;
            case RING -> List.of("class_sessions", "template_classes", "training_slots", "training_bookings", "ring_blocks", "placements")
                    .stream().anyMatch(collection -> count(collection, Criteria.where("ringId").is(id)) > 0);
            case FAQ -> false;
        };
    }
}
