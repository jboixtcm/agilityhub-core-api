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
}
