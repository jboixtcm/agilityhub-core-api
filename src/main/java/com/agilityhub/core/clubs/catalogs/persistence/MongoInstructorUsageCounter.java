package com.agilityhub.core.clubs.catalogs.persistence;

import com.agilityhub.core.clubs.catalogs.application.InstructorUsageCounter;
import com.agilityhub.core.shared.domain.TenantEntity;
import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.Clock;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Repository;

/** Scheduling port projection; the model uses instructorIds arrays on both class types. */
@Repository
public class MongoInstructorUsageCounter extends TenantRepository<MongoInstructorUsageCounter.Reference> implements InstructorUsageCounter {
    public record Reference(String id, String clubId) implements TenantEntity { }
    private final Clock clock;
    public MongoInstructorUsageCounter(MongoTemplate mongo, Clock clock) { super(mongo, Reference.class); this.clock = clock; }
    private long count(String collection, Criteria criteria) { return mongo.count(tenantQuery().addCriteria(criteria), collection); }
    private long templateClasses(String field, String id) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("classes." + field).is(id)), org.bson.Document.class, "week_templates")
                .stream().flatMap(template -> template.getList("classes", org.bson.Document.class).stream())
                .filter(item -> item.get(field) instanceof java.util.List<?> values ? values.contains(id) : id.equals(item.get(field))).count();
    }
    public Usage usage(String id) {
        return new Usage(count("class_sessions", Criteria.where("instructorIds").is(id).and("startsAt").gt(clock.instant()).and("state").ne("CANCELLED")),
                templateClasses("instructorIds", id));
    }
    public boolean hasReferences(String id) {
        return count("class_sessions", Criteria.where("instructorIds").is(id)) + templateClasses("instructorIds", id) > 0;
    }
}
