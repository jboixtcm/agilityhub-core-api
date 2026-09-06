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
    private final OutboxRepository records;
    private final Map<String, DomainEventHandler<?>> handlers;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final int maxAttempts;

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
                        transactions.executeWithoutResult(status -> {
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

    private <T extends com.agilityhub.core.shared.domain.DomainEvent> void deliver(
            DomainEventHandler<T> handler, DomainEventRecord record) {
        try {
            handler.handle(record.id(), mapper.readValue(record.eventJson(), handler.eventClass()));
        } catch (Exception failure) { throw new IllegalStateException("Outbox handler failed", failure); }
    }
}
