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
    public void ensureIndexes() {
        mongo.indexOps(Account.class).ensureIndex(new Index().on("email", Direction.ASC).unique().named("account_email"));
    }
}
