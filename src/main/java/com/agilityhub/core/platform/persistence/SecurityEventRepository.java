package com.agilityhub.core.platform.persistence;

import com.agilityhub.core.shared.persistence.GlobalRepository;
import java.time.Duration;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOptions;
import org.springframework.stereotype.Repository;

@Repository
public class SecurityEventRepository extends GlobalRepository<SecurityEvent> {
    public SecurityEventRepository(MongoTemplate mongo) { super(mongo, SecurityEvent.class); }
    public void append(SecurityEvent event) { mongo.insert(event); }
    public void ensureIndexes(int retentionDays) {
        var indexes = mongo.indexOps(SecurityEvent.class);
        var retention = Duration.ofDays(retentionDays);
        var existing = indexes.getIndexInfo().stream()
                .filter(index -> index.getName().equals("security_event_retention")).findFirst();
        if (existing.isEmpty()) {
            indexes.ensureIndex(new Index().on("at", Direction.ASC).expire(retention).named("security_event_retention"));
        } else if (!existing.get().getExpireAfter().equals(java.util.Optional.of(retention))) {
            indexes.alterIndex("security_event_retention", IndexOptions.expireAfter(retention));
        }
    }
}
