package com.agilityhub.core.clubs.scheduling.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.*;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;

@Repository
public class WeekRepository extends TenantRepository<Week> {
    public WeekRepository(MongoTemplate mongo) { super(mongo, Week.class); }
    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        mongo.indexOps(Week.class).ensureIndex(new Index().on("clubId", ASC).on("startDate", ASC).unique().named("week_club_start"));
    }
}
