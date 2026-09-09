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
    public Usage usage(String id) {
        return new Usage(count("class_sessions", Criteria.where("instructorIds").is(id).and("startsAt").gt(clock.instant()).and("status").ne("CANCELLED")),
                count("template_classes", Criteria.where("instructorIds").is(id)));
    }
    public boolean hasReferences(String id) {
        return count("class_sessions", Criteria.where("instructorIds").is(id)) + count("template_classes", Criteria.where("instructorIds").is(id)) > 0;
    }
}
