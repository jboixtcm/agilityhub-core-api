package com.agilityhub.core.shared.application;

import com.agilityhub.core.shared.persistence.DomainEventRecord;
import com.agilityhub.core.shared.persistence.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Audit OUT-01: a consumer that throws no longer keeps the consumers after it from their delivery. Every matching consumer
 * runs in its own attempt; the record backs off once all have had their turn, and the retry runs only the unprocessed ones.
 * The Mongo side (checkpoints, rollback, back-off) is covered by `OutboxIT`.
 */
class OutboxDispatcherTest {
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    private static String consumer(String beanName) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(beanName.getBytes(StandardCharsets.UTF_8));
    }
    private static DomainEventRecord record(int attempts, Map<String, Instant> processedAt) {
        return new DomainEventRecord("event-1", "club-a", "ClubUpdated", "Club", "club-a", NOW, Map.of(), null, null, null,
                "\"payload\"", DomainEventRecord.Status.PENDING, attempts, NOW, null, null, "claim-1", 0, processedAt);
    }
    private static DomainEventHandler<String> handler(String type, List<String> calls, String name, boolean fails) {
        return new DomainEventHandler<>() {
            public String eventType() { return type; }
            public Class<String> eventClass() { return String.class; }
            public void handle(String eventId, String event) {
                calls.add(name);
                if (fails) { throw new IllegalArgumentException("fictional handler failure"); }
            }
        };
    }
    private static OutboxDispatcher dispatcher(OutboxRepository records, Map<String, DomainEventHandler<?>> handlers) {
        return new OutboxDispatcher(records, handlers, new TransactionTemplate(mock(PlatformTransactionManager.class)),
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry(), 10);
    }

    @Test void OUT_01_aFailingConsumerDoesNotKeepTheConsumersAfterItFromTheirDelivery() {
        var records = mock(OutboxRepository.class);
        var record = record(1, Map.of());
        when(records.claim(any())).thenReturn(record, (DomainEventRecord) null);
        var calls = new ArrayList<String>();
        Map<String, DomainEventHandler<?>> handlers = new LinkedHashMap<>();
        handlers.put("first", handler("ClubUpdated", calls, "first", true));
        handlers.put("unrelated", handler("ParameterChanged", calls, "unrelated", false));
        handlers.put("second", handler("ClubUpdated", calls, "second", false));

        dispatcher(records, handlers).dispatch();

        assertThat(calls).containsExactly("first", "second");
        verify(records).processed(record, consumer("second"), NOW);
        verify(records, never()).processed(same(record), eq(consumer("first")), any());
        verify(records, never()).published(any(), any());
        var failure = org.mockito.ArgumentCaptor.forClass(Exception.class);
        verify(records).failed(same(record), eq(NOW), eq(10), failure.capture());
        assertThat(failure.getValue()).isInstanceOf(IllegalStateException.class).hasMessage("Outbox handler failed")
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test void OUT_01_theRetryRunsOnlyTheUnprocessedConsumerAndPublishesOnceItSucceeds() {
        var records = mock(OutboxRepository.class);
        var record = record(2, Map.of(consumer("second"), NOW));
        when(records.claim(any())).thenReturn(record, (DomainEventRecord) null);
        var calls = new ArrayList<String>();
        Map<String, DomainEventHandler<?>> handlers = new LinkedHashMap<>();
        handlers.put("first", handler("ClubUpdated", calls, "first", false));
        handlers.put("second", handler("ClubUpdated", calls, "second", false));

        dispatcher(records, handlers).dispatch();

        assertThat(calls).containsExactly("first");
        verify(records).processed(record, consumer("first"), NOW);
        verify(records).published(record, NOW);
        verify(records, never()).failed(any(), any(), anyInt(), any());
    }

    @Test void OUT_01_twoFailingConsumersBothRunAndTheRecordBacksOffOnce() {
        var records = mock(OutboxRepository.class);
        var record = record(1, Map.of());
        when(records.claim(any())).thenReturn(record, (DomainEventRecord) null);
        var calls = new ArrayList<String>();
        Map<String, DomainEventHandler<?>> handlers = new LinkedHashMap<>();
        handlers.put("first", handler("ClubUpdated", calls, "first", true));
        handlers.put("second", handler("ClubUpdated", calls, "second", true));

        dispatcher(records, handlers).dispatch();

        assertThat(calls).containsExactly("first", "second");
        verify(records, never()).processed(any(), any(), any());
        verify(records, never()).published(any(), any());
        verify(records, times(1)).failed(same(record), eq(NOW), eq(10), any());
    }
    /**
     * OUT-01 pass 2 (owner decision of 13 Nov 2026): a record whose failure was its last (the repository says it is FAILED now)
     * tells every alert once — ids, type, attempts and the error type, never the payload —, an alert that throws does not keep
     * the next one or the next record from running, and a failure that still leaves the record PENDING alerts nobody.
     */
    @Test void OUT_01_aRecordThatEndsFailedTellsEveryAlertOnceAndAnEarlierFailureNone() {
        var records = mock(OutboxRepository.class);
        var last = record(36, Map.of()); var earlier = new DomainEventRecord("event-2", "club-a", "ClubUpdated", "Club", "club-a", NOW, Map.of(), null, null, null,
                "\"payload\"", DomainEventRecord.Status.PENDING, 3, NOW, null, null, "claim-2", 0, Map.of());
        when(records.claim(any())).thenReturn(last, earlier, (DomainEventRecord) null);
        when(records.failed(same(last), any(), anyInt(), any())).thenReturn(true);
        when(records.failed(same(earlier), any(), anyInt(), any())).thenReturn(false);
        var told = new ArrayList<OutboxFailureAlerts.FailedEvent>();
        OutboxFailureAlerts throwing = event -> { told.add(event); throw new IllegalStateException("fictional alert failure"); };
        OutboxFailureAlerts recording = told::add;
        var dispatcher = new OutboxDispatcher(records, Map.of("first", handler("ClubUpdated", new ArrayList<>(), "first", true)),
                new TransactionTemplate(mock(PlatformTransactionManager.class)), new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry(),
                OutboxDispatcher.MAX_ATTEMPTS, () -> List.of(throwing, recording));

        dispatcher.dispatch();

        verify(records).failed(same(earlier), eq(NOW), eq(36), any());
        assertThat(told).hasSize(2).containsOnly(new OutboxFailureAlerts.FailedEvent("event-1", "club-a", "ClubUpdated", "Club", 36, "IllegalStateException", NOW));
    }

    /** OUT-01 pass 2: the back-off doubles from 1 s to an hour, so the 36th attempt comes about a day after the first (before: 10 over ~8.5 min). */
    @Test void OUT_01_theRetryHorizonIsAboutADayWithAGrowingBackOff() {
        assertThat(OutboxRepository.backoff(1)).hasSeconds(1);
        assertThat(OutboxRepository.backoff(2)).hasSeconds(2);
        assertThat(OutboxRepository.backoff(12)).hasSeconds(2_048);
        assertThat(OutboxRepository.backoff(13)).isEqualTo(java.time.Duration.ofHours(1));
        assertThat(OutboxRepository.backoff(35)).isEqualTo(java.time.Duration.ofHours(1));
        assertThat(OutboxRepository.backoff(0)).hasSeconds(1);
        var horizon = java.time.Duration.ZERO;
        for (int attempt = 1; attempt < OutboxDispatcher.MAX_ATTEMPTS; attempt++) { horizon = horizon.plus(OutboxRepository.backoff(attempt)); }
        assertThat(horizon).isBetween(java.time.Duration.ofHours(23), java.time.Duration.ofHours(25));
        assertThat(OutboxDispatcher.MAX_ATTEMPTS).isEqualTo(36);
    }
}
