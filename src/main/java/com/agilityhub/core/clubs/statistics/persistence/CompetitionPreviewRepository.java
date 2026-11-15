package com.agilityhub.core.clubs.statistics.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

@Repository
public class CompetitionPreviewRepository extends TenantRepository<CompetitionPreviewRecord> {
    public CompetitionPreviewRepository(MongoTemplate mongo) { super(mongo, CompetitionPreviewRecord.class); }
    public long erasePreviews() { return mongo.remove(tenantQuery(), CompetitionPreviewRecord.class).getDeletedCount(); }
    @jakarta.annotation.PostConstruct
    public void indexes() {
        mongo.indexOps(CompetitionPreviewRecord.class).createIndex(new Index().on("expiresAt", Sort.Direction.ASC).expire(java.time.Duration.ZERO));
    }
}
