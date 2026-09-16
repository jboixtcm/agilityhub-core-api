package com.agilityhub.core.clubs.scheduling.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.*;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;

@Repository
public class ClassSessionRepository extends TenantRepository<ClassSession> {
    public ClassSessionRepository(MongoTemplate mongo) { super(mongo, ClassSession.class); }
    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        mongo.indexOps(ClassSession.class).ensureIndex(new Index().on("clubId", ASC).on("date", ASC).on("state", ASC).named("class_club_date_state"));
        mongo.indexOps(ClassSession.class).ensureIndex(new Index().on("clubId", ASC).on("startsAt", ASC).named("class_club_start"));
        mongo.indexOps(ClassSession.class).ensureIndex(new Index().on("clubId", ASC).on("weekId", ASC).named("class_club_week"));
        mongo.indexOps(ClassSession.class).ensureIndex(new Index().on("clubId", ASC).on("ringId", ASC).on("startsAt", ASC).named("class_club_ring_start"));
        mongo.indexOps(ClassSession.class).ensureIndex(new Index().on("clubId", ASC).on("state", ASC).on("endsAt", ASC).named("class_club_state_end"));
    }
    public boolean hasClasses() { return mongo.exists(tenantQuery(), ClassSession.class); }
}
