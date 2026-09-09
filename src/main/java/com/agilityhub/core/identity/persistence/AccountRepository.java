package com.agilityhub.core.identity.persistence;

import com.agilityhub.core.identity.domain.Email;
import com.agilityhub.core.shared.persistence.GlobalRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository extends GlobalRepository<Account> {
    public AccountRepository(MongoTemplate mongo) { super(mongo, Account.class); }
    public Optional<Account> findByEmail(String email) {
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("email").is(Email.normalize(email))), Account.class));
    }
    public void recordLogin(String id, String clientId, Instant now) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)),
                new Update().set("lastLoginAt", now).set("lastLoginClientId", clientId), Account.class);
    }
    public void markEmailStatus(String id, String email, com.agilityhub.core.shared.application.NotificationAccounts.EmailStatus status) {
        var query = Query.query(Criteria.where("_id").is(id).and("email").is(email)
                .and("emailStatus").ne(com.agilityhub.core.shared.application.NotificationAccounts.EmailStatus.COMPLAINED));
        mongo.updateFirst(query, new Update().set("emailStatus", status), Account.class);
    }
    public boolean createIfAbsent(Account account) {
        var update = new Update().setOnInsert("_id", account.id()).setOnInsert("email", account.email())
                .setOnInsert("name", account.name()).setOnInsert("locale", account.locale())
                .setOnInsert("passwordHash", account.passwordHash())
                .setOnInsert("platformRoles", account.platformRoles()).setOnInsert("status", account.status())
                .setOnInsert("security", account.security()).setOnInsert("externalIds", account.externalIds())
                .setOnInsert("onboardingPending", account.onboardingPending()).setOnInsert("createdAt", account.createdAt())
                .setOnInsert("consents", account.consents()).setOnInsert("consentPostponements", account.consentPostponements())
                .setOnInsert("createdSource", account.createdSource());
        return mongo.upsert(Query.query(Criteria.where("email").is(account.email())), update, Account.class).getUpsertedId() != null;
    }
    /** Serialize account-wide session limits and credential changes with concurrent grants. */
    public void touchSessions(String id) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update().inc("sessionSequence", 1), Account.class);
    }
    public void lockout(String id, com.agilityhub.core.identity.domain.LoginLockout state) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update().set("security.failedLogins", state.failures())
                .set("security.failedLoginWindowStartedAt", state.windowStartedAt()).set("security.lockedUntil", state.lockedUntil())
                .set("security.lockoutLevel", state.level()), Account.class);
    }
    public void verifyEmail(String id, Instant now) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id).and("emailVerifiedAt").is(null)),
                new Update().set("emailVerifiedAt", now), Account.class);
    }
    public void patch(String id, String locale, String name) {
        var update = new Update();
        if (locale != null) { update.set("locale", locale); }
        if (name != null) { update.set("name", name); }
        if (!update.getUpdateObject().isEmpty()) { mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), update, Account.class); }
    }
    public void completeOnboarding(String id, Account.Consent consent) {
        var update = new Update().set("onboardingPending", false);
        if (consent != null) { update.push("consents", consent); }
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), update, Account.class);
    }
    public void postponeConsent(String id, java.util.List<Account.ConsentPostponement> postponements) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)),
                new Update().set("consentPostponements", postponements), Account.class);
    }
    public void password(String id, String hash, Instant now) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update().set("passwordHash", hash)
                .set("security.passwordChangedAt", now).inc("security.tokenFamilyVersion", 1)
                .set("security.failedLogins", 0).set("security.lockedUntil", null)
                .set("security.failedLoginWindowStartedAt", null).set("security.lockoutLevel", 0), Account.class);
    }
    public void revokeAll(String id, Instant now) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update().inc("security.tokenFamilyVersion", 1).set("security.accessRevokedAt", now), Account.class);
    }
    public void ensureIndexes() {
        mongo.indexOps(Account.class).ensureIndex(new Index().on("email", Direction.ASC).unique().named("account_email"));
    }
}
