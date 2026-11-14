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
    /**
     * OUT-01 (owner decision of 13 Nov 2026): the attempts of a record before it ends `FAILED`. With the back-off of
     * {@link OutboxRepository#backoff} (1 s doubling to 1 h) the 36th attempt comes about 24 hours after the first (before, 10
     * attempts over about 8.5 minutes).
     */
    public static final int MAX_ATTEMPTS = 36;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(OutboxDispatcher.class);
    private final OutboxRepository records;
    private final Map<String, DomainEventHandler<?>> handlers;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final int maxAttempts;
    private final TransactionRetries retries;
    /** OUT-01: told of every record that ends `FAILED` (the platform admins' N-57). */
    private final java.util.function.Supplier<java.util.List<OutboxFailureAlerts>> alerts;

    public OutboxDispatcher(OutboxRepository records, Map<String, DomainEventHandler<?>> handlers,
                            TransactionTemplate transactions, ObjectMapper mapper, Clock clock,
                            MeterRegistry metrics, int maxAttempts) {
        this(records, handlers, transactions, mapper, clock, metrics, maxAttempts, java.util.List::of);
    }
    /** With the alerts of a record that ends `FAILED` (OUT-01): the application's dispatcher. */
    public OutboxDispatcher(OutboxRepository records, Map<String, DomainEventHandler<?>> handlers,
                            TransactionTemplate transactions, ObjectMapper mapper, Clock clock,
                            MeterRegistry metrics, int maxAttempts, java.util.function.Supplier<java.util.List<OutboxFailureAlerts>> alerts) {
        if (maxAttempts < 1) { throw new IllegalArgumentException("maxAttempts must be positive"); }
        this.records = records;
        this.handlers = new LinkedHashMap<>(handlers);
        this.transactions = transactions;
        this.mapper = mapper;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
        this.retries = new TransactionRetries(metrics);
        this.alerts = java.util.Objects.requireNonNull(alerts);
        Gauge.builder("outbox.pending", records, r -> r.count(DomainEventRecord.Status.PENDING)).register(metrics);
        Gauge.builder("outbox.failed", records, r -> r.count(DomainEventRecord.Status.FAILED)).register(metrics);
    }

    /**
     * INC-40 (E11-T10): on its own thread, so neither the job tick nor a provider send delays the outbox. Audit OUT-01: each
     * matching consumer runs in its own attempt, so one that throws does not keep the consumers after it from their delivery;
     * the record backs off (or ends FAILED) once every consumer has had its turn, and a retry runs only the unprocessed ones.
     * Pass 2 (owner decision of 13 Nov 2026): the back-off grows to an hour and the record ends FAILED about a day after its
     * first attempt ({@link #MAX_ATTEMPTS}); then the {@link OutboxFailureAlerts} are told (the platform admins' N-57).
     */
    @Scheduled(fixedDelay = 1000, scheduler = SchedulerConfiguration.OUTBOX)
    public void dispatch() {
        for (int i = 0; i < 100; i++) {
            DomainEventRecord record = records.claim(clock.instant());
            if (record == null) { return; }
            try {
                if (record.attempts() > maxAttempts) { throw new IllegalStateException("Claim attempts exhausted"); }
                Exception firstFailure = null;
                for (var entry : handlers.entrySet()) {
                    String consumer = Base64.getUrlEncoder().withoutPadding()
                            .encodeToString(entry.getKey().getBytes(StandardCharsets.UTF_8));
                    var handler = entry.getValue();
                    if (handler.eventType().equals(record.type()) && !record.processedAt().containsKey(consumer)) {
                        try {
                            retries.inTransaction(RETRY_CONTEXT, CONFLICT_ATTEMPTS, transactions, status -> {
                                records.lock(record, clock.instant());
                                deliver(handler, record);
                                records.processed(record, consumer, clock.instant());
                            });
                        } catch (Exception failure) {
                            if (firstFailure == null) { firstFailure = failure; }
                        }
                    }
                }
                if (firstFailure != null) { throw firstFailure; }
                records.published(record, clock.instant());
            } catch (Exception failure) {
                if (records.failed(record, clock.instant(), maxAttempts, failure)) { alert(record, failure); }
            }
        }
    }

    /** OUT-01: a record has just ended `FAILED`; each alert is told once, and one that throws never stops the dispatch. */
    private void alert(DomainEventRecord record, Exception failure) {
        var failed = new OutboxFailureAlerts.FailedEvent(record.id(), record.clubId(), record.type(), record.aggregateType(), record.attempts(),
                failure.getClass().getSimpleName(), clock.instant());
        LOG.error("Outbox event FAILED after its retries eventId={} type={} clubId={} attempts={} error={}", failed.id(), failed.type(), failed.clubId(),
                failed.attempts(), failed.error());
        for (var alert : alerts.get()) {
            try { alert.failed(failed); }
            catch (RuntimeException unalerted) {
                LOG.error("Outbox failure alert failed eventId={} type={} error={}", failed.id(), failed.type(), unalerted.getClass().getSimpleName());
            }
        }
    }

    private <T> void deliver(DomainEventHandler<T> handler, DomainEventRecord record) {
        try {
            handler.handle(record.id(), mapper.readValue(record.eventJson(), handler.eventClass()));
        } catch (Exception failure) { throw new IllegalStateException("Outbox handler failed", failure); }
    }
}
