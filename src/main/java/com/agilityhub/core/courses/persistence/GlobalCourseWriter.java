package com.agilityhub.core.courses.persistence;

import com.agilityhub.core.courses.domain.CourseTypes.OwnerType;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.persistence.GlobalRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/**
 * The only writer of tenantless courses: the AgilityHub library (`AGILITYHUB`, R-16-14) and account courses (`ACCOUNT`, their
 * author). A club course never passes here: it always goes through the tenant {@link CourseRepository}.
 */
@Repository
public class GlobalCourseWriter extends GlobalRepository<Course> {
    public GlobalCourseWriter(MongoTemplate mongo) { super(mongo, Course.class); }
    private static void requireGlobal(Course course) {
        if (course.clubId() != null || course.ownerType() == OwnerType.CLUB) { throw new IllegalArgumentException("Club courses use the tenant repository"); }
    }
    public Course insert(Course course) { requireGlobal(course); return mongo.insert(course); }
    /** Optimistic replacement of a live global course: another version or a deleted course is `409 STALE_VERSION`. */
    public void update(Course next, long expectedVersion) {
        requireGlobal(next);
        var query = Query.query(Criteria.where("_id").is(next.id()).and("clubId").is(null).and("ownerType").is(next.ownerType().name())
                .and("version").is(expectedVersion).and("deletedAt").is(null));
        if (mongo.findAndReplace(query, next) == null) { throw new ApiException(ErrorCode.STALE_VERSION); }
    }
    @Override public java.util.Optional<Course> findById(String id) { throw new UnsupportedOperationException("Read through GlobalCourseRepository"); }
    @Override public Course save(Course course) { throw new UnsupportedOperationException("Use insert or update"); }
}
