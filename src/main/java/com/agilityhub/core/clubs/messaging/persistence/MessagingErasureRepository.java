package com.agilityhub.core.clubs.messaging.persistence;

import com.agilityhub.core.shared.application.TenantContext;
import java.util.Collection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/**
 * S14 R-14-15, the messaging part (S11): a notification to or about an erased member keeps code, channel, states and `sentAt` and
 * loses title, body, SMS body, rendered variables, the recipient's name and every delivery target; its address becomes the
 * account's pseudonym. Announcements forget the member (`memberIds`) and their free-text search (`q`); push subscriptions go.
 */
@Repository
public class MessagingErasureRepository {
    private final MongoTemplate mongo;
    public MessagingErasureRepository(MongoTemplate mongo) { this.mongo = mongo; }
    private static Criteria club() { return Criteria.where("clubId").is(TenantContext.require()); }

    public long eraseNotifications(Collection<String> memberIds, Collection<String> accountIds) {
        var about = club().orOperator(Criteria.where("recipient.memberId").in(memberIds), Criteria.where("subject.memberId").in(memberIds),
                Criteria.where("recipient.accountId").in(accountIds), Criteria.where("accountId").in(accountIds));
        long changed = mongo.updateMulti(Query.query(about), new Update().unset("title").unset("body").unset("smsBody").unset("variables")
                .unset("recipient.displayName").unset("error"), Notification.class).getModifiedCount();
        // `$[]` needs the array: rows of the E1 compatibility block have no deliveries.
        mongo.updateMulti(Query.query(about).addCriteria(Criteria.where("deliveries.0").exists(true)), new Update().unset("deliveries.$[].target"), Notification.class);
        return changed;
    }
    /** `recipientEmail` (and the recipient's address) → the account's pseudonym, the same as `Account.email` (S14 R-14-15, E1-T03). */
    public long pseudonymiseAddresses(String accountId, String pseudonym) {
        var own = club().orOperator(Criteria.where("recipient.accountId").is(accountId), Criteria.where("accountId").is(accountId));
        long changed = mongo.updateMulti(Query.query(own).addCriteria(Criteria.where("recipientEmail").ne(null)), new Update().set("recipientEmail", pseudonym),
                Notification.class).getModifiedCount();
        return changed + mongo.updateMulti(Query.query(club().and("recipient.accountId").is(accountId).and("recipient.email").ne(null)),
                new Update().set("recipient.email", pseudonym), Notification.class).getModifiedCount();
    }
    public long eraseAnnouncements(Collection<String> memberIds) {
        return mongo.updateMulti(Query.query(club().and("memberIds").in(memberIds)), new Update().pullAll("memberIds", memberIds.toArray()).unset("q"),
                Announcement.class).getModifiedCount();
    }
    public long deletePushSubscriptions(Collection<String> accountIds) {
        return mongo.remove(Query.query(club().and("accountId").in(accountIds)), PushSubscription.class).getDeletedCount();
    }

    /** S14 R-14-13: the member's notifications of the current club (to the member or its account), raw. */
    public java.util.List<org.bson.Document> notifications(String memberId, String accountId) {
        var to = accountId == null ? Criteria.where("recipient.memberId").is(memberId)
                : new Criteria().orOperator(Criteria.where("recipient.memberId").is(memberId), Criteria.where("recipient.accountId").is(accountId),
                        Criteria.where("accountId").is(accountId));
        return mongo.find(Query.query(club()).addCriteria(to).with(org.springframework.data.domain.Sort.by("createdAt", "_id")), org.bson.Document.class, "notifications");
    }
}
