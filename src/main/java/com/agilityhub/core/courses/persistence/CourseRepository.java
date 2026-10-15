package com.agilityhub.core.courses.persistence;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.*;

@Repository
public class CourseRepository extends CourseTenantRepository<Course> {
    /** The list projection: the model and its history (up to 11 × 256 KB) never leave the database for a list. */
    static final String[] HEAVY = {"normalizedJson", "history"};
    public CourseRepository(MongoTemplate mongo) {
        super(mongo, Course.class);
        mongo.indexOps(Course.class).ensureIndex(new Index().on("clubId", ASC).on("deletedAt", ASC).on("updatedAt", DESC).named("club_deleted_updated"));
        mongo.indexOps(Course.class).ensureIndex(new Index().on("ownerType", ASC).on("visibility", ASC).on("deletedAt", ASC).named("owner_visibility_deleted"));
        mongo.indexOps(Course.class).ensureIndex(new Index().on("ownerAccountId", ASC).on("deletedAt", ASC).named("account_deleted"));
    }
    /** The club's live courses without their model (R-16-03: the caller's visibility is applied by the library). */
    public List<Course> library() {
        var query = tenantQuery().addCriteria(Criteria.where("ownerType").is("CLUB").and("deletedAt").is(null));
        query.fields().exclude(HEAVY);
        return mongo.find(query, Course.class);
    }
    /** Optimistic replacement of a live club course: another version, tenant or a deleted course is `409 STALE_VERSION`. */
    public void update(Course next, long expectedVersion) {
        var query = tenantQuery(next.clubId()).addCriteria(Criteria.where("_id").is(next.id()).and("version").is(expectedVersion).and("deletedAt").is(null));
        if (mongo.findAndReplace(query, next) == null) { throw new ApiException(ErrorCode.STALE_VERSION); }
    }
}
