package com.agilityhub.core.courses.persistence;

import com.agilityhub.core.shared.persistence.GlobalRepository;
import java.util.Optional;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/** Read-only global lookup; club documents never cross this boundary. The application checks visibility and authorship. */
@Repository
public class GlobalCourseRepository extends GlobalRepository<Course> {
    public GlobalCourseRepository(MongoTemplate mongo) { super(mongo, Course.class); }
    @Override public Optional<Course> findById(String id) {
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("_id").is(id)
                .and("clubId").is(null).and("ownerType").in("AGILITYHUB", "ACCOUNT").and("deletedAt").is(null)), Course.class));
    }
    @Override public Course save(Course course) { throw new UnsupportedOperationException("Global course lookup is read only"); }
}
