package com.agilityhub.core.platform.application.privacy;

import com.agilityhub.core.platform.domain.events.PrivacyEvent;
import com.agilityhub.core.platform.persistence.privacy.ErasureRequestRepository;
import com.agilityhub.core.shared.application.DomainEventHandler;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * S14 §7 `ErasureFilesCleanup`: on `AccountErased`, deletes the request's `filesPending[]` from their stores, one at a time and
 * outside any transaction (a store call is never inside a Mongo transaction), and closes the request at `ERASED` with `erasedAt`
 * once none is left (§5 `ERASING → ERASED`). A failure is retried by the outbox, and by the next daily `erasures` run.
 */
@Service
public class ErasureFilesCleanup {
    private final ErasureRequestRepository requests;
    private final Map<String, PrivacyFileStore> stores;
    private final Clock clock;
    public ErasureFilesCleanup(ErasureRequestRepository requests, List<PrivacyFileStore> stores, Clock clock) {
        this.requests = requests; this.clock = clock;
        this.stores = stores.stream().collect(Collectors.toMap(PrivacyFileStore::store, Function.identity()));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean clean(String requestId) {
        var request = requests.findById(requestId).orElse(null);
        if (request == null || !"ERASING".equals(request.status())) { return false; }
        for (String file : request.filesPending()) {
            int colon = file.indexOf(':');
            var store = colon < 0 ? null : stores.get(file.substring(0, colon));
            if (store == null) { throw new IllegalStateException("No privacy file store for a pending file of request " + requestId); }
            store.delete(file.substring(colon + 1));
            requests.fileDeleted(requestId, file, clock.instant());
        }
        return requests.close(requestId, clock.instant());
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
