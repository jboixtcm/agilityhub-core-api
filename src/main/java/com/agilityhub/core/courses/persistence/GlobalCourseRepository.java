package com.agilityhub.core.courses.persistence;

import com.agilityhub.core.shared.persistence.GlobalRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/** Read-only global lookup; club documents never cross this boundary. The application checks visibility and authorship. */
@Repository
public class GlobalCourseRepository extends GlobalRepository<Course> {
    public GlobalCourseRepository(MongoTemplate mongo) { super(mongo, Course.class); }
    private static Criteria global() { return Criteria.where("clubId").is(null).and("ownerType").in("AGILITYHUB", "ACCOUNT").and("deletedAt").is(null); }
    @Override public Optional<Course> findById(String id) {
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("_id").is(id)).addCriteria(global()), Course.class));
    }
    /**
     * R-16-03, without the model: the AgilityHub library and the `PUBLIC` account courses, plus the caller's own account courses
     * (`accountId`; null for none). The library applies the visibility rules again on every item.
     */
    public List<Course> library(String accountId) {
        var readable = accountId == null ? Criteria.where("visibility").is("PUBLIC")
                : new Criteria().orOperator(Criteria.where("visibility").is("PUBLIC"), Criteria.where("ownerAccountId").is(accountId));
        var query = Query.query(global()).addCriteria(readable);
        query.fields().exclude(CourseRepository.HEAVY);
        return mongo.find(query, Course.class);
    }
    /** R-16-14: the AgilityHub library itself (`/platform/courses`), without the model. */
    public List<Course> agilityHub() {
        var query = Query.query(Criteria.where("clubId").is(null).and("ownerType").is("AGILITYHUB").and("deletedAt").is(null));
        query.fields().exclude(CourseRepository.HEAVY);
        return mongo.find(query, Course.class);
    }
    @Override public Course save(Course course) { throw new UnsupportedOperationException("Global course lookup is read only"); }
}
