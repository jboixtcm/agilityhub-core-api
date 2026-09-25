package com.agilityhub.core.clubs.census.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;

/** `signup_notification_admissions` (R-04-20, E3-T12 round 2): one decision per event and notification; the first one wins. */
@Repository
public class SignupNotificationAdmissionRepository extends TenantRepository<SignupNotificationAdmission> {
    public SignupNotificationAdmissionRepository(MongoTemplate mongo) { super(mongo, SignupNotificationAdmission.class); }
    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        mongo.indexOps(SignupNotificationAdmission.class).ensureIndex(new Index().on("expiresAt", ASC).expire(Duration.ZERO).named("signup_admission_ttl"));
    }
    public Optional<SignupNotificationAdmission> decision(String eventId, String notificationCode) {
        return findById(SignupNotificationAdmission.id(eventId, notificationCode));
    }
    /** The stored decision, and whether this call stored it ({@code false}: a concurrent delivery of the event stored one first). */
    public record Decided(SignupNotificationAdmission admission, boolean stored) { }
    /** Stores {@code decision}, or returns the one a concurrent delivery of the same event stored first. */
    public Decided decide(SignupNotificationAdmission decision) {
        try { return new Decided(insert(decision), true); }
        catch (DuplicateKeyException taken) { return new Decided(findById(decision.id()).orElseThrow(() -> taken), false); }
    }
    /**
     * E3-T15: starts the retention of a decision once the outbox transaction that marks its event's consumer processed has
     * committed; before, the decision has no `expiresAt`.
     */
    public void retain(String eventId, String notificationCode, Instant expiresAt) {
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(SignupNotificationAdmission.id(eventId, notificationCode))),
                Update.update("expiresAt", expiresAt), SignupNotificationAdmission.class);
    }
}
