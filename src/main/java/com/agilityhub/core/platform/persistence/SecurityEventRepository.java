package com.agilityhub.core.platform.persistence;

import com.agilityhub.core.shared.persistence.GlobalRepository;
import java.time.Duration;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Repository;

@Repository
public class SecurityEventRepository extends GlobalRepository<SecurityEvent> {
    static final String RETENTION_INDEX = "security_event_retention";
    public SecurityEventRepository(MongoTemplate mongo) { super(mongo, SecurityEvent.class); }
    public void append(SecurityEvent event) { mongo.insert(event); }
    /**
     * S14 R-14-16 (f): the `at` TTL index at `security.eventRetentionDays`, whatever the name of the single-field `at` index a
     * volume already has. A retention that changed (or an `at` index without one) is a drop and a create, never `collMod`
     * (INC-82, E11-T16): the production user holds `readWrite`, which has `createIndex` and `dropIndex` but not `collMod`.
     */
    public void ensureIndexes(int retentionDays) {
        var indexes = mongo.indexOps(SecurityEvent.class);
        var retention = Duration.ofDays(retentionDays);
        var existing = indexes.getIndexInfo().stream().filter(index -> index.getName().equals(RETENTION_INDEX)
                || index.getIndexFields().size() == 1 && index.getIndexFields().getFirst().getKey().equals("at")).toList();
        if (existing.size() == 1 && existing.getFirst().getName().equals(RETENTION_INDEX) && existing.getFirst().getIndexFields().size() == 1
                && existing.getFirst().getExpireAfter().equals(java.util.Optional.of(retention))) { return; }
        existing.forEach(index -> indexes.dropIndex(index.getName()));
        indexes.createIndex(new Index().on("at", Direction.ASC).expire(retention).named(RETENTION_INDEX));
    }
}
