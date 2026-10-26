package com.agilityhub.core.clubs.messaging.persistence;

import com.agilityhub.core.shared.persistence.GlobalRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** Privileged provider infrastructure, never a user-facing lookup. Callbacks must first correlate a stored recipient. */
@Repository
public class BrevoWebhookRepository extends GlobalRepository<BrevoWebhookReceipt> {
    public BrevoWebhookRepository(MongoTemplate mongo) { super(mongo, BrevoWebhookReceipt.class); }
    public void insert(BrevoWebhookReceipt receipt) { mongo.insert(receipt); }
    public boolean suppressed(String email) { return mongo.exists(Query.query(Criteria.where("_id").is(addressKey(email))), BrevoEmailSuppression.class); }
    public void suppress(String email, String reason, Instant at) {
        mongo.upsert(Query.query(Criteria.where("_id").is(addressKey(email))),
                new Update().setOnInsert("reason", reason).setOnInsert("createdAt", at), BrevoEmailSuppression.class);
    }
    public static String addressKey(String email) { return digest(email.strip().toLowerCase(Locale.ROOT)); }
    public static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
