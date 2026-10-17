package com.agilityhub.core.identity.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/**
 * S14 R-14-15, the identity part. Tokens, magic links, handoff codes, impersonation grants and OIDC sessions are technical and
 * deleted; memberships go to `ERASED` without roles; the account keeps its id, creation, consents and history and loses its
 * e-mail (to the pseudonym), name, password, external ids and verification.
 */
@Repository
public class IdentityErasureRepository {
    private final MongoTemplate mongo;
    public IdentityErasureRepository(MongoTemplate mongo) { this.mongo = mongo; }

    public List<Membership> memberships(String accountId) {
        return mongo.find(Query.query(Criteria.where("accountId").is(accountId)), Membership.class);
    }
    /** The account's membership of one club: `ERASED`, no roles (an erased membership can never be used again). */
    public long eraseMembership(String accountId, String clubId, Instant at) {
        return mongo.updateMulti(Query.query(Criteria.where("accountId").is(accountId).and("clubId").is(clubId).and("status").ne(Membership.Status.ERASED)),
                new Update().set("status", Membership.Status.ERASED).set("roles", List.of()).unset("defaultProfile").unset("adminProfile")
                        .set("updatedAt", at).inc("version", 1), Membership.class).getModifiedCount();
    }
    /** The club access the account no longer has: its refresh tokens, magic links and handoff codes of that club. */
    public long deleteClubAccess(String accountId, String clubId) {
        var query = Query.query(Criteria.where("accountId").is(accountId).and("clubId").is(clubId));
        return mongo.remove(query, RefreshToken.class).getDeletedCount() + mongo.remove(query, MagicLinkToken.class).getDeletedCount()
                + mongo.remove(query, HandoffCode.class).getDeletedCount();
    }
    /** Impersonation grants of the club naming an erased member. */
    public long deleteGrantsFor(String clubId, Collection<String> memberIds) {
        return mongo.remove(Query.query(Criteria.where("clubId").is(clubId).and("impersonatedMemberId").in(memberIds)), ImpersonationGrant.class).getDeletedCount();
    }

    /** The account itself (R-14-15 `Account`), only while it is not erased yet; live access tokens are revoked by the family bump. */
    public boolean eraseAccount(String accountId, String pseudonym, String name, Instant at) {
        var update = new Update().set("email", pseudonym).set("name", name).unset("passwordHash").set("externalIds", Map.of())
                .unset("emailVerifiedAt").set("platformRoles", List.of()).set("status", Account.Status.ERASED)
                .inc("security.tokenFamilyVersion", 1).set("security.accessRevokedAt", at);
        return mongo.updateFirst(Query.query(Criteria.where("_id").is(accountId).and("status").ne(Account.Status.ERASED)), update, Account.class)
                .getModifiedCount() == 1;
    }
    /** Every token, link, code, grant and OIDC session of the account, in every club. */
    public long deleteAccountAccess(String accountId) {
        var own = Query.query(Criteria.where("accountId").is(accountId));
        long deleted = mongo.remove(own, RefreshToken.class).getDeletedCount() + mongo.remove(own, MagicLinkToken.class).getDeletedCount()
                + mongo.remove(own, HandoffCode.class).getDeletedCount() + mongo.remove(own, "oidc_browser_sessions").getDeletedCount()
                + mongo.remove(own, "oidc_codes").getDeletedCount();
        return deleted + mongo.remove(Query.query(new Criteria().orOperator(Criteria.where("actorAccountId").is(accountId),
                Criteria.where("impersonatedAccountId").is(accountId))), ImpersonationGrant.class).getDeletedCount();
    }
    public long eraseAllMemberships(String accountId, Instant at) {
        return mongo.updateMulti(Query.query(Criteria.where("accountId").is(accountId).and("status").ne(Membership.Status.ERASED)),
                new Update().set("status", Membership.Status.ERASED).set("roles", List.of()).unset("defaultProfile").unset("adminProfile")
                        .set("updatedAt", at).inc("version", 1), Membership.class).getModifiedCount();
    }

    /** S14 R-14-13: the account and its membership of one club, raw. */
    public org.bson.Document account(String accountId) {
        return mongo.findOne(Query.query(Criteria.where("_id").is(accountId)), org.bson.Document.class, "accounts");
    }
    public List<org.bson.Document> membership(String accountId, String clubId) {
        return mongo.find(Query.query(Criteria.where("accountId").is(accountId).and("clubId").is(clubId)), org.bson.Document.class, "memberships");
    }
}
