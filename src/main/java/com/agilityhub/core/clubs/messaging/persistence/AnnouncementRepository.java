package com.agilityhub.core.clubs.messaging.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;
import static org.springframework.data.domain.Sort.Direction.DESC;

/** `announcements` (E7-T04, R-11-13): one document per batch (`_id` = `batchId`), read by the engine and by the log, by club and date. */
@Repository
public class AnnouncementRepository extends TenantRepository<Announcement> {
    public AnnouncementRepository(MongoTemplate mongo) { super(mongo, Announcement.class); }
    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        mongo.indexOps(Announcement.class).ensureIndex(new Index().on("clubId", ASC).on("createdAt", DESC).named("announcement_club_created"));
    }
}
