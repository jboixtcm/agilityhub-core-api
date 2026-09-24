package com.agilityhub.core.clubs.followup.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;

/** S10 §3 `followup_read_marks`: one document per staff account and club. */
@Repository
public class FollowupReadMarkRepository extends TenantRepository<FollowupReadMark> {
    public FollowupReadMarkRepository(MongoTemplate mongo) { super(mongo, FollowupReadMark.class); }
    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        mongo.indexOps(FollowupReadMark.class).ensureIndex(new Index().on("clubId", ASC).on("accountId", ASC).unique().named("followup_read_club_account"));
    }
}
