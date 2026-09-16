package com.agilityhub.core.clubs.scheduling.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.*;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;

@Repository
public class RingBlockRepository extends TenantRepository<RingBlock> {
    public RingBlockRepository(MongoTemplate mongo) { super(mongo, RingBlock.class); }
    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        mongo.indexOps(RingBlock.class).ensureIndex(new Index().on("clubId", ASC).on("ringId", ASC).on("from", ASC).on("to", ASC).named("block_club_ring_range"));
        mongo.indexOps(RingBlock.class).ensureIndex(new Index().on("clubId", ASC).on("from", ASC).named("block_club_from"));
    }

    public java.util.List<RingBlock> between(java.time.Instant from, java.time.Instant to) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("state").is("ACTIVE").and("from").lt(to).and("to").gt(from)), RingBlock.class);
    }
    public java.util.List<RingBlock> forActivity(String id) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("activityId").is(id).and("state").is("ACTIVE")), RingBlock.class);
    }
    public RingBlock update(RingBlock next, long version) {
        var saved = mongo.findAndReplace(tenantQuery(next.clubId()).addCriteria(Criteria.where("_id").is(next.id()).and("version").is(version)),
                next, org.springframework.data.mongodb.core.FindAndReplaceOptions.options().returnNew());
        if (saved == null) { throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.STALE_VERSION); }
        return saved;
    }
}
