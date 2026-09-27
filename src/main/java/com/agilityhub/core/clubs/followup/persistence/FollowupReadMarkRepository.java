package com.agilityhub.core.clubs.followup.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;

/**
 * S10 §3 `followup_read_marks`: one document per staff account and club (unique `{clubId, accountId}`), written with
 * single-document upserts, so «Marcar-ho tot com a llegit» is O(1) whatever the number of rows (R-10-13).
 */
@Repository
public class FollowupReadMarkRepository extends TenantRepository<FollowupReadMark> {
    public FollowupReadMarkRepository(MongoTemplate mongo) { super(mongo, FollowupReadMark.class); }
    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        mongo.indexOps(FollowupReadMark.class).ensureIndex(new Index().on("clubId", ASC).on("accountId", ASC).unique().named("followup_read_club_account"));
    }
    private Query mark(String accountId) { return tenantQuery().addCriteria(Criteria.where("accountId").is(accountId)); }
    private static Update created(Update update, String id, Instant now) { return update.setOnInsert("_id", id).setOnInsert("createdAt", now).set("updatedAt", now); }
    public Optional<FollowupReadMark> find(String accountId) { return Optional.ofNullable(mongo.findOne(mark(accountId), FollowupReadMark.class)); }
    /** «Marcar-ho tot com a llegit»: `readAllAt = now`, `readItemIds = []`. */
    public void readAll(String accountId, Instant now) {
        mongo.upsert(mark(accountId), created(new Update().set("readAllAt", now).set("readItemIds", List.of()), id(accountId), now), FollowupReadMark.class);
    }
    /** A click: `readItemIds += id`, and the ids that {@code pruned} leaves out are dropped. */
    public void read(String accountId, String itemId, Collection<String> pruned, Instant now) {
        if (!pruned.isEmpty()) { mongo.updateFirst(mark(accountId), new Update().pullAll("readItemIds", pruned.toArray()), FollowupReadMark.class); }
        mongo.upsert(mark(accountId), created(new Update().addToSet("readItemIds", itemId), id(accountId), now), FollowupReadMark.class);
    }
    /** A MEMBER_NOTE row that changed is unread again for everyone but its author (R-10-12): its id leaves every mark. */
    public void unreadForEveryone(String itemId, Instant now) {
        mongo.updateMulti(tenantQuery().addCriteria(Criteria.where("readItemIds").is(itemId)), new Update().pull("readItemIds", itemId).set("updatedAt", now),
                FollowupReadMark.class);
    }
    private static String id(String accountId) {
        return java.util.UUID.nameUUIDFromBytes(("followup:read:" + com.agilityhub.core.shared.application.TenantContext.require() + ":" + accountId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }
}
