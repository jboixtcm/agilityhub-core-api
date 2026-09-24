package com.agilityhub.core.clubs.followup.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;

/** S10 §3 `followup_items`; the D14 list and the consumers arrive with E6-T03. */
@Repository
public class FollowupItemRepository extends TenantRepository<FollowupItem> {
    public FollowupItemRepository(MongoTemplate mongo) { super(mongo, FollowupItem.class); }
    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        var indexes = mongo.indexOps(FollowupItem.class);
        indexes.ensureIndex(new Index().on("clubId", ASC).on("activityAt", ASC).named("followup_club_activity"));
        indexes.ensureIndex(new Index().on("clubId", ASC).on("kind", ASC).on("activityAt", ASC).named("followup_club_kind_activity"));
    }
}
