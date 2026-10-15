package com.agilityhub.core.configuration;

import com.agilityhub.core.courses.application.ports.CourseReferencePort;
import com.agilityhub.core.shared.domain.TenantEntity;
import com.agilityhub.core.shared.persistence.TenantRepository;
import java.util.List;
import org.bson.Document;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
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
        /** The ids only, oldest class first (`class_sessions` of the tenant). */
        @Override public List<String> classSessionIds(String placementId) {
            var query = tenantQuery().addCriteria(Criteria.where("placementId").is(placementId)).with(Sort.by("startsAt", "_id"));
            query.fields().include("_id");
            return mongo.find(query, Document.class, "class_sessions").stream().map(document -> document.get("_id").toString()).toList();
        }
        private boolean exists(String collection, String id) {
            return mongo.exists(tenantQuery().addCriteria(Criteria.where("_id").is(id)), collection);
        }
    }
}
