package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.persistence.SignupNotificationAdmission;
import com.agilityhub.core.clubs.census.persistence.SignupNotificationAdmissionRepository;
import com.agilityhub.core.shared.application.RateLimits;
import com.agilityhub.core.shared.application.SignupCapabilities;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * R-04-20 (E3-T09 … E3-T16), moved unchanged out of the E3 `SignupNotifications` when E7-T02 gave N-01 to the engine: the
 * anonymous routes cannot flood one address. At most the club's `signup.rateLimit.notificationsPerRecipientPerHour` (3 by
 * default) N-39 per account and applicant's N-01 per address, per club; the rest are skipped and logged with a hash, never
 * the address. Each event is decided once per notification ({@link SignupNotificationAdmission}), so a delivery retried after
 * its admission still sends and only new events count; the decision is stored before the allowance is charged, outside any
 * transaction (a rolled-back outbox delivery never loses a charged decision), and it has no expiry until its event is
 * processed ({@link #retainAfterCommit}).
 */
@Service
public class SignupRecipientCap {
    /** The notification whose recipient cap each event type decides. */
    static final Map<String, String> CAPPED = Map.of("SignupRecognitionRequested", "N-39", "SignupSubmitted", "N-01");
    private static final Logger LOG = LoggerFactory.getLogger(SignupRecipientCap.class);
    /** E3-T15: one decision at a time per instance, shared by every club, recipient and code (E3-T16, review #5). */
    private final ReentrantLock deciding = new ReentrantLock();
    private final CensusAccess access; private final RateLimits limits; private final SignupCapabilities capabilities;
    private final SignupNotificationAdmissionRepository admissions; private final Clock clock; private final TransactionTemplate outside;

    public SignupRecipientCap(CensusAccess access, RateLimits limits, SignupCapabilities capabilities, SignupNotificationAdmissionRepository admissions,
            Clock clock, PlatformTransactionManager transactions) {
        this.access = access; this.limits = limits; this.capabilities = capabilities; this.admissions = admissions; this.clock = clock;
        this.outside = new TransactionTemplate(transactions); outside.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    /** Whether the event's capped notification may reach `recipient`: the stored decision, or a new one. */
    public boolean admitted(String eventId, String clubId, String eventType, String recipient) {
        String code = Objects.requireNonNull(CAPPED.get(eventType), "Uncapped signup notification event");
        String hash = capabilities.fingerprint(recipient.toLowerCase(Locale.ROOT));
        var admission = outside.execute(tx -> admissions.decision(eventId, code).orElseGet(() -> decide(clubId, eventId, code, hash)));
        if (admission.admitted()) { return true; }
        LOG.info("Signup notification capped per recipient code={} clubId={} recipientHash={}", code, clubId, hash);
        return false;
    }
    /**
     * E3-T15 step 1: the bucket is probed, the decision stored, and only then does an admission take its token. A failed
     * write throws before the charge, so the retry decides again on an untouched bucket; a decision a concurrent delivery
     * stored first is used as it is, and charged by that delivery.
     */
    SignupNotificationAdmission decide(String clubId, String eventId, String code, String hash) {
        var limit = limits.limit(RateLimits.Route.SIGNUP_RECIPIENT, access.config().get("signup.rateLimit", Map.class));
        String subject = clubId + ":" + code + ":" + hash;
        deciding.lock();
        try {
            boolean admitted = limits.available(RateLimits.Route.SIGNUP_RECIPIENT, subject, limit);
            var decided = admissions.decide(new SignupNotificationAdmission(SignupNotificationAdmission.id(eventId, code), clubId, eventId, code, admitted, clock.instant(), null));
            if (decided.stored() && admitted) { limits.charge(RateLimits.Route.SIGNUP_RECIPIENT, subject, limit); }
            return decided.admission();
        } finally { deciding.unlock(); }
    }
    /**
     * E3-T15 step 2: called inside the outbox transaction that marks the event processed; the decision's retention (its
     * event's, `jobs.retention.domainEventsDays`) starts once that transaction commits. If it fails, the decision is kept
     * without expiry (logged): a leftover, never a lost admission.
     */
    public void retainAfterCommit(String eventId, String clubId, String eventType) {
        String code = CAPPED.get(eventType);
        if (code == null || !TransactionSynchronizationManager.isSynchronizationActive()) { return; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try (var tenant = TenantContext.open(clubId)) {
                    int days = access.config().get("jobs.retention.domainEventsDays", Integer.class);
                    outside.executeWithoutResult(tx -> admissions.retain(eventId, code, clock.instant().plus(Duration.ofDays(days))));
                } catch (RuntimeException failure) {
                    LOG.warn("Signup notification admission kept without expiry eventId={} code={} error={}", eventId, code, failure.getClass().getSimpleName());
                }
            }
        });
    }
}
