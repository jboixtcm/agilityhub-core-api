package com.agilityhub.core.identity.persistence;

import com.agilityhub.core.shared.persistence.GlobalRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;

@Repository
public class SigningKeyRepository extends GlobalRepository<SigningKeyRing> {
    public SigningKeyRepository(MongoTemplate mongo) { super(mongo, SigningKeyRing.class); }
    public void initialize(SigningKeyRing ring) {
        try { mongo.insert(ring); } catch (org.springframework.dao.DuplicateKeyException concurrentStart) { /* The first instance owns initialization. */ }
    }
    public boolean rotate(SigningKeyRing ring) {
        return mongo.updateFirst(Query.query(Criteria.where("_id").is(ring.id()).and("generation").is(ring.generation() - 1)),
                new Update().set("encryptedKeys", ring.encryptedKeys()).set("generation", ring.generation()).set("rotatedAt", ring.rotatedAt()),
                SigningKeyRing.class).getModifiedCount() == 1;
    }
}
