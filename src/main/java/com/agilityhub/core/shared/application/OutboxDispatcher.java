package com.agilityhub.core.shared.application;

import com.agilityhub.core.shared.persistence.DomainEventRecord;
import com.agilityhub.core.shared.persistence.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

public class OutboxDispatcher {
    /**
     * E7-T04 round 3 (S11 T-11-31; CI red at `b786a0c`): a consumer's transaction that meets another writer runs again in the
     * same dispatch, after the shared backoff, at most this many times ({@link TransactionRetries#inTransaction}). Two
     * `ReminderDue` of one booking insert the same N-13 `dedupKey` (unique `{clubId, dedupKey}`): the one that loses gets a
     * `WriteConflict` (112) while the other is open, and then finds the stored notice. Once these attempts run out, the record
     * backs off like any other failure and a later dispatch delivers it again (R-11-09).
     */
    public static final int CONFLICT_ATTEMPTS = 5;
    /** The `context` tag of the outbox's retries in `core.transactions.retries` and `core.transactions.exhausted`. */
    public static final String RETRY_CONTEXT = "outbox";
    private final OutboxRepository records;
    private final Map<String, DomainEventHandler<?>> handlers;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final int maxAttempts;
    private final TransactionRetries retries;

    public OutboxDispatcher(OutboxRepository records, Map<String, DomainEventHandler<?>> handlers,
                            TransactionTemplate transactions, ObjectMapper mapper, Clock clock,
                            MeterRegistry metrics, int maxAttempts) {
        if (maxAttempts < 1) { throw new IllegalArgumentException("maxAttempts must be positive"); }
        this.records = records;
        this.handlers = new LinkedHashMap<>(handlers);
        this.transactions = transactions;
        this.mapper = mapper;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
        this.retries = new TransactionRetries(metrics);
        Gauge.builder("outbox.pending", records, r -> r.count(DomainEventRecord.Status.PENDING)).register(metrics);
        Gauge.builder("outbox.failed", records, r -> r.count(DomainEventRecord.Status.FAILED)).register(metrics);
    }

    @Scheduled(fixedDelay = 1000)
    public void dispatch() {
        for (int i = 0; i < 100; i++) {
            DomainEventRecord record = records.claim(clock.instant());
            if (record == null) { return; }
            try {
                if (record.attempts() > maxAttempts) { throw new IllegalStateException("Claim attempts exhausted"); }
                for (var entry : handlers.entrySet()) {
                    String consumer = Base64.getUrlEncoder().withoutPadding()
                            .encodeToString(entry.getKey().getBytes(StandardCharsets.UTF_8));
                    var handler = entry.getValue();
                    if (handler.eventType().equals(record.type()) && !record.processedAt().containsKey(consumer)) {
                        retries.inTransaction(RETRY_CONTEXT, CONFLICT_ATTEMPTS, transactions, status -> {
                            records.lock(record, clock.instant());
                            deliver(handler, record);
                            records.processed(record, consumer, clock.instant());
                        });
                    }
                }
                records.published(record, clock.instant());
            } catch (Exception failure) {
                records.failed(record, clock.instant(), maxAttempts, failure);
            }
        }
    }

    private <T> void deliver(DomainEventHandler<T> handler, DomainEventRecord record) {
        try {
            handler.handle(record.id(), mapper.readValue(record.eventJson(), handler.eventClass()));
        } catch (Exception failure) { throw new IllegalStateException("Outbox handler failed", failure); }
    }
}
