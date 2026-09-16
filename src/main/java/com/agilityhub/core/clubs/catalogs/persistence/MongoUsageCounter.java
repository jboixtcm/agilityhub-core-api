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
    private Criteria future(String field, String id, String stateField) {
        return Criteria.where(field).is(id).and("startsAt").gt(clock.instant()).and(stateField).ne("CANCELLED");
    }
    private long templateClasses(String field, String id) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("classes." + field).is(id)), org.bson.Document.class, "week_templates")
                .stream().flatMap(template -> template.getList("classes", org.bson.Document.class).stream())
                .filter(item -> item.get(field) instanceof java.util.List<?> values ? values.contains(id) : id.equals(item.get(field))).count();
    }
    @Override public Map<String, Long> usage(CatalogKind kind, String id) {
        return switch (kind) {
            case LEVEL -> Map.of("activeDogs", count("dogs", Criteria.where("levelId").is(id).and("status").is("ACTIVE")),
                    "futureClassSessions", count("class_sessions", future("levelIds", id, "state")),
                    "templateClasses", templateClasses("levelIds", id));
            case RING -> Map.of("futureClassSessions", count("class_sessions", future("ringId", id, "state")),
                    "futureTrainingBookings", count("training_bookings", future("ringId", id, "status")),
                    "templateClasses", templateClasses("ringId", id),
                    "ringBlocks", count("ring_blocks", Criteria.where("ringId").is(id).and("state").is("ACTIVE")));
            case FAQ -> Map.of();
        };
    }
    @Override public boolean hasReferences(CatalogKind kind, String id) {
        return switch (kind) {
            case LEVEL -> count("dogs", Criteria.where("levelId").is(id))
                    + count("class_sessions", Criteria.where("levelIds").is(id))
                    + templateClasses("levelIds", id) > 0;
            case RING -> List.of("class_sessions", "training_slots", "training_bookings", "ring_blocks", "placements")
                    .stream().anyMatch(collection -> count(collection, Criteria.where("ringId").is(id)) > 0) || templateClasses("ringId", id) > 0;
            case FAQ -> false;
        };
    }
}
