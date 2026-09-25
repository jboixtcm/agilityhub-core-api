package com.agilityhub.core.clubs.census.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.Duration;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
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
    /** Stores {@code decision}, or returns the one a concurrent delivery of the same event stored first. */
    public SignupNotificationAdmission decide(SignupNotificationAdmission decision) {
        try { return insert(decision); }
        catch (DuplicateKeyException taken) { return findById(decision.id()).orElseThrow(() -> taken); }
    }
}
