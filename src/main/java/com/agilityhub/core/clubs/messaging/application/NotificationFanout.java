package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.clubs.messaging.domain.NotificationEvent;
import com.agilityhub.core.clubs.messaging.persistence.Notification;
import com.agilityhub.core.clubs.messaging.persistence.NotificationRepository;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S15 R-15-11 «pic de càrrega»: mass notifications (N-33 to every active member) are written in batches: the APP feed
 * rows with one `insertMany` per {@value #FEED_BATCH} rows, and the PUSH intents in batches of {@value #PUSH_BATCH} on
 * a dedicated pool that never shares threads with the API. Ids are deterministic, so a redelivery writes nothing twice.
 */
@Service
public class NotificationFanout {
    static final int FEED_BATCH = 500, PUSH_BATCH = 100;
    public record Row(String id, String accountId, String locale, Map<String, Object> variables) { }
    private final NotificationRepository notifications; private final EventPublisher events; private final TransactionTemplate transactions; private final Clock clock;
    private final ExecutorService pool;
    public NotificationFanout(NotificationRepository notifications, EventPublisher events, PlatformTransactionManager manager, Clock clock) {
        this.notifications = notifications; this.events = events; this.transactions = new TransactionTemplate(manager); this.clock = clock;
        var counter = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(2, runnable -> {
            var thread = new Thread(runnable, "notification-fanout-" + counter.incrementAndGet()); thread.setDaemon(true); return thread;
        });
    }
    @PreDestroy void close() { pool.shutdownNow(); }

    /** APP rows (feed), SENT on insertion like {@link SystemNotificationService#appOnce}. Returns the rows written. */
    @Transactional(propagation = Propagation.NEVER)
    public int app(String code, List<Row> rows) {
        int written = 0;
        for (int from = 0; from < rows.size(); from += FEED_BATCH) {
            written += batch(code, "APP", Notification.Status.SENT, rows.subList(from, Math.min(rows.size(), from + FEED_BATCH)));
        }
        return written;
    }
    /** PUSH intents (QUEUED, or SKIPPED_MODULE_OFF without PUSH): no push sender exists before E7. */
    @Transactional(propagation = Propagation.NEVER)
    public int push(String code, List<Row> rows, boolean enabled) {
        String club = TenantContext.require();
        var futures = new ArrayList<CompletableFuture<Integer>>();
        for (int from = 0; from < rows.size(); from += PUSH_BATCH) {
            var slice = List.copyOf(rows.subList(from, Math.min(rows.size(), from + PUSH_BATCH)));
            futures.add(CompletableFuture.supplyAsync(() -> {
                try (var tenant = TenantContext.open(club)) {
                    return batch(code, "PUSH", enabled ? Notification.Status.QUEUED : Notification.Status.SKIPPED_MODULE_OFF, slice);
                }
            }, pool));
        }
        return futures.stream().mapToInt(CompletableFuture::join).sum();
    }

    private int batch(String code, String channel, Notification.Status status, List<Row> rows) {
        return transactions.execute(tx -> {
            var existing = notifications.existingIds(rows.stream().map(Row::id).toList());
            var fresh = new ArrayList<Notification>(); var variables = new HashMap<String, Map<String, Object>>();
            var now = clock.instant(); String club = TenantContext.require();
            for (Row row : rows) {
                if (existing.contains(row.id())) { continue; }
                fresh.add(new Notification(row.id(), club, row.accountId(), code, channel, status, null, status == Notification.Status.SENT ? now : null,
                        null, null, row.locale(), now));
                variables.put(row.id(), row.variables());
            }
            notifications.insertBatch(fresh, variables);
            fresh.forEach(n -> events.publish(new NotificationEvent(NotificationEvent.Kind.NotificationQueued, club, n.id(), now)));
            return fresh.size();
        });
    }
}
