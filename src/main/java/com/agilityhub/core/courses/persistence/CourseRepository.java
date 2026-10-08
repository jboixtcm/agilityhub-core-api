package com.agilityhub.core.courses.persistence;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.*;

@Repository
public class CourseRepository extends CourseTenantRepository<Course> {
    public CourseRepository(MongoTemplate mongo) {
        super(mongo, Course.class);
        mongo.indexOps(Course.class).ensureIndex(new Index().on("clubId", ASC).on("deletedAt", ASC).on("updatedAt", DESC).named("club_deleted_updated"));
        mongo.indexOps(Course.class).ensureIndex(new Index().on("ownerType", ASC).on("visibility", ASC).on("deletedAt", ASC).named("owner_visibility_deleted"));
        mongo.indexOps(Course.class).ensureIndex(new Index().on("ownerAccountId", ASC).on("deletedAt", ASC).named("account_deleted"));
    }
}
