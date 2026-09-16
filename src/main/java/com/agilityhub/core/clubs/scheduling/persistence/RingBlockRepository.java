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
}
