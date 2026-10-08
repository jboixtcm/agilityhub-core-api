package com.agilityhub.core.configuration;

import com.agilityhub.core.courses.application.ports.CourseReferencePort;
import com.agilityhub.core.shared.domain.TenantEntity;
import com.agilityhub.core.shared.persistence.TenantRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;

/** Composition-root adapter for contract reference checks, without loading another context's mutable aggregate. */
@Configuration(proxyBeanMethods = false)
public class CourseReferenceConfiguration {
    @Bean CourseReferencePort courseReferences(MongoTemplate mongo) { return new References(mongo); }
    private record Reference(String id, String clubId) implements TenantEntity { }
    private static final class References extends TenantRepository<Reference> implements CourseReferencePort {
        References(MongoTemplate mongo) { super(mongo, Reference.class); }
        @Override public boolean activityExists(String id) { return exists("activities", id); }
        @Override public boolean dogExists(String id) { return exists("dogs", id); }
        private boolean exists(String collection, String id) {
            return mongo.exists(tenantQuery().addCriteria(Criteria.where("_id").is(id)), collection);
        }
    }
}
