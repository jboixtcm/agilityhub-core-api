package com.agilityhub.core.platform.persistence;

import com.agilityhub.core.shared.persistence.GlobalRepository;
import java.time.Duration;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Repository;

@Repository
public class SecurityEventRepository extends GlobalRepository<SecurityEvent> {
    public SecurityEventRepository(MongoTemplate mongo) { super(mongo, SecurityEvent.class); }
    public void append(SecurityEvent event) { mongo.insert(event); }
    public void ensureIndexes(int retentionDays) {
        mongo.indexOps(SecurityEvent.class).ensureIndex(new Index().on("at", Direction.ASC)
                .expire(Duration.ofDays(retentionDays)).named("security_event_retention"));
    }
}
