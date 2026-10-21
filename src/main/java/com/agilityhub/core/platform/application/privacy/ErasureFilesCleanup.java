package com.agilityhub.core.platform.application.privacy;

import com.agilityhub.core.platform.domain.events.PrivacyEvent;
import com.agilityhub.core.platform.persistence.privacy.ErasureRequestRepository;
import com.agilityhub.core.shared.application.DomainEventHandler;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S14 §7 `ErasureFilesCleanup`: deletes a request's `filesPending[]` from their stores, one at a time and outside any transaction
 * (a store call is never inside a Mongo transaction), and closes an `ERASING` request at `ERASED` with `erasedAt` once none is left
 * (§5 `ERASING → ERASED`). It runs on `AccountErased`, and also right after a club's part of an `ACCOUNT` request commits without
 * completing it (E11-T11): an erased club's files leave the store while the request waits, is `BLOCKED` between clubs or is
 * cancelled, which keeps its status. A failure is retried by the outbox, and by the next daily `erasures` run of any of its clubs.
 */
@Service
public class ErasureFilesCleanup {
    private static final Logger log = LoggerFactory.getLogger(ErasureFilesCleanup.class);
    private final ErasureRequestRepository requests;
    private final Map<String, PrivacyFileStore> stores;
    private final Clock clock;
    private final TransactionTemplate outside;
    public ErasureFilesCleanup(ErasureRequestRepository requests, List<PrivacyFileStore> stores, Clock clock, PlatformTransactionManager manager) {
        this.requests = requests; this.clock = clock;
        this.stores = stores.stream().collect(Collectors.toMap(PrivacyFileStore::store, Function.identity()));
        this.outside = new TransactionTemplate(manager); this.outside.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    /** True when it deleted a file or closed the request. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean clean(String requestId) {
        var request = requests.findById(requestId).orElse(null);
        if (request == null) { return false; }
        for (String file : request.filesPending()) {
            int colon = file.indexOf(':');
            var store = colon < 0 ? null : stores.get(file.substring(0, colon));
            if (store == null) { throw new IllegalStateException("No privacy file store for a pending file of request " + requestId); }
            store.delete(file.substring(colon + 1));
            requests.fileDeleted(requestId, file, clock.instant());
        }
        boolean closed = "ERASING".equals(request.status()) && requests.close(requestId, clock.instant());
        return closed || !request.filesPending().isEmpty();
    }

    /**
     * The cleanup once the caller's transaction commits (nothing on a rollback); at once without one. A failure here is only
     * logged: the files stay in `filesPending[]` and the next daily run deletes them.
     */
    public void cleanAfterCommit(String requestId) { cleanAfterCommit(requestId, () -> { }); }

    /**
     * {@link #cleanAfterCommit(String)}, and {@code cleaned} runs on the same thread once the cleanup deleted a file or closed the
     * request, never after a failure. INC-74 (E11-T13): the daily run's item counts it in `filesCleaned` of that run.
     */
    public void cleanAfterCommit(String requestId, Runnable cleaned) {
        Runnable work = () -> {
            boolean done = false;
            try { done = Boolean.TRUE.equals(outside.execute(status -> clean(requestId))); }
            catch (RuntimeException failure) { log.warn("Erasure files of request {} left for the daily retry: {}", requestId, failure.getClass().getName()); }
            if (done) { cleaned.run(); }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) { work.run(); return; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { @Override public void afterCommit() { work.run(); } });
    }

    @Configuration(proxyBeanMethods = false)
    static class Consumers {
        /** The bean name is the durable outbox consumer id. */
        @Bean DomainEventHandler<PrivacyEvent> erasureFilesCleanupConsumer(ErasureFilesCleanup cleanup) {
            return new DomainEventHandler<>() {
                public String eventType() { return "AccountErased"; }
                public Class<PrivacyEvent> eventClass() { return PrivacyEvent.class; }
                public void handle(String eventId, PrivacyEvent event) { cleanup.clean(String.valueOf(event.payload().get("requestId"))); }
            };
        }
    }
}
