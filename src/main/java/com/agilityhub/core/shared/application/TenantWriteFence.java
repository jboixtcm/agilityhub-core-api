package com.agilityhub.core.shared.application;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.persistence.TenantWriteCounterRepository;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;

/** Transaction registrations keep maintenance exclusive without serializing nested application transactions. */
@Service
public class TenantWriteFence {
    private final TenantWriteCounterRepository counters;
    private final TransactionTemplate independent;
    private final TransactionRetries retries;
    public TenantWriteFence(TenantWriteCounterRepository counters, PlatformTransactionManager manager, TransactionRetries retries) {
        this.counters = counters;
        this.retries = retries;
        independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Register before touching data; reset refuses while any application transaction can still commit. */
    public void begin(Collection<String> clubs) {
        Pending pending = pending();
        for (String club : clubs) {
            if (!pending.maintenance.contains(club) && !pending.active.contains(club)) {
                independently(() -> counters.begin(club));
                pending.active.add(club);
            }
        }
    }

    public void written(String clubId) {
        if (clubId == null) { return; }
        // Explicit audit writes outside a transaction are also wrapped by the template interceptor.
        if (!TransactionSynchronizationManager.isActualTransactionActive()) { return; }
        begin(List.of(clubId)); pending().dirty.add(clubId);
    }

    /** Maintenance takes the document lock before its business work and flushes its own writes before checkpointing. */
    public long lock() {
        String club = TenantContext.require(); Pending pending = pending();
        if (pending.active.contains(club)) { throw new ApiException(ErrorCode.MIGRATION_ALREADY_APPLIED); }
        pending.flush(club);
        long sequence = counters.lock();
        pending.maintenance.add(club);
        return sequence;
    }

    private Pending pending() {
        for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof Pending pending) { return pending; }
        }
        var pending = new Pending(); TransactionSynchronizationManager.registerSynchronization(pending); return pending;
    }

    private void independently(Runnable action) {
        for (int attempt = 1; ; attempt++) {
            try { independent.executeWithoutResult(status -> action.run()); return; }
            catch (RuntimeException failure) {
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    if (cause instanceof com.mongodb.MongoException mongo && mongo.hasErrorLabel("UnknownTransactionCommitResult")) {
                        throw failure;
                    }
                }
                if (!TransactionRetries.conflict(failure)) { throw failure; }
                if (attempt >= 6) { retries.exhausted("tenant-write-fence"); throw failure; }
                retries.retried("tenant-write-fence", failure);
                try { Thread.sleep(TransactionRetries.jitter()); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw failure; }
            }
        }
    }

    private final class Pending implements TransactionSynchronization {
        private final Set<String> active = new TreeSet<>(), maintenance = new TreeSet<>(), dirty = new TreeSet<>();
        void flush(String club) {
            if (maintenance.contains(club) && dirty.remove(club)) { counters.written(club); }
        }
        @Override public void beforeCommit(boolean readOnly) { maintenance.forEach(this::flush); }
        @Override public void afterCompletion(int status) {
            for (String club : active) {
                // UNKNOWN must invalidate reset too. If cleanup cannot commit, the retained registration fails closed.
                try { independently(() -> counters.finish(club, status != STATUS_ROLLED_BACK && dirty.contains(club))); }
                catch (RuntimeException failure) {
                    org.slf4j.LoggerFactory.getLogger(TenantWriteFence.class)
                            .warn("Tenant write registration cleanup unconfirmed club={}", club);
                }
            }
        }
    }
}
