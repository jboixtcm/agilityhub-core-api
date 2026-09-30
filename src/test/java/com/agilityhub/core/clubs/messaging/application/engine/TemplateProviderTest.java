package com.agilityhub.core.clubs.messaging.application.engine;

import com.agilityhub.core.clubs.messaging.domain.NotificationCatalog;
import com.agilityhub.core.clubs.messaging.persistence.MessageTemplate;
import com.agilityhub.core.clubs.messaging.persistence.MessageTemplateRepository;
import com.agilityhub.core.shared.application.TenantContext;
import com.mongodb.MongoException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.UncategorizedMongoDbException;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * E7-T03 round 2, item 7 (R-11-01, T-11-31): the first use of a template survives a concurrent first use. The loser of the
 * unique `{clubId, code}` meets a `WriteConflict` (112) while the winner has not committed and a duplicate key once it has;
 * it reads the winner's template outside the caller's transaction and, while it is not there yet, waits and tries again —
 * never an error to the engine. Anything else still fails at once. Before the fix only `DuplicateKeyException` was caught,
 * so the write conflict of CI's T-11-31 run (`ca83d97`) reached the engine.
 */
class TemplateProviderTest {
    private final MessageTemplateRepository templates = mock(MessageTemplateRepository.class);
    private final TemplateProvider provider = new TemplateProvider(templates, MessageTemplateSeed.load(), Clock.fixed(Instant.parse("2026-10-05T08:00:00Z"), ZoneOffset.UTC),
            mock(PlatformTransactionManager.class));
    private static RuntimeException writeConflict() { return new UncategorizedMongoDbException("Write conflict", new MongoException(112, "WriteConflict")); }

    @Test void T_11_31_aWriteConflictOnTheFirstUseReadsTheWinnersTemplateOnceItIsCommitted() {
        var spec = NotificationCatalog.byCode("N-13").orElseThrow();
        try (var tenant = TenantContext.open("club-a")) {
            var winner = provider.seed(spec, List.of("ca", "es"), "ca");
            // The caller's read, then: a write conflict (not committed yet: absent), then a duplicate key (committed: present).
            when(templates.findByCode("N-13")).thenReturn(Optional.empty(), Optional.empty(), Optional.of(winner));
            when(templates.insert(any(MessageTemplate.class))).thenThrow(writeConflict()).thenThrow(new DuplicateKeyException("E11000"));
            assertThat(provider.forCode(spec, List.of("ca", "es"), "ca")).isSameAs(winner);
            verify(templates, times(2)).insert(any(MessageTemplate.class));
            verify(templates, times(3)).findByCode("N-13");
        }
    }

    @Test void T_11_31_aConflictThatNeverSettlesAndAnyOtherFailureAreTheCallers() {
        var spec = NotificationCatalog.byCode("N-13").orElseThrow();
        try (var tenant = TenantContext.open("club-a")) {
            when(templates.findByCode("N-13")).thenReturn(Optional.empty());
            when(templates.insert(any(MessageTemplate.class))).thenThrow(writeConflict());
            assertThatThrownBy(() -> provider.forCode(spec, List.of("ca"), "ca")).isInstanceOf(UncategorizedMongoDbException.class);
            verify(templates, times(TemplateProvider.FIRST_USE_ATTEMPTS)).insert(any(MessageTemplate.class));
            reset(templates);
            when(templates.findByCode("N-13")).thenReturn(Optional.empty());
            when(templates.insert(any(MessageTemplate.class))).thenThrow(new IllegalStateException("disk full"));
            assertThatThrownBy(() -> provider.forCode(spec, List.of("ca"), "ca")).isInstanceOf(IllegalStateException.class);
            verify(templates, times(1)).insert(any(MessageTemplate.class));
        }
    }

    @Test void R_11_01_d9sListFallsBackToOneCodeAtATimeWhenAFirstUseWins() {
        try (var tenant = TenantContext.open("club-a")) {
            when(templates.findAll()).thenReturn(List.of());
            var stored = new java.util.HashMap<String, MessageTemplate>();
            when(templates.findByCode(any())).thenAnswer(call -> Optional.ofNullable(stored.get(call.<String>getArgument(0))));
            // The batch meets a write conflict; one code at a time, each insert then succeeds.
            when(templates.insert(any(MessageTemplate.class))).thenThrow(writeConflict()).thenAnswer(call -> {
                MessageTemplate template = call.getArgument(0); stored.put(template.code(), template); return template; });
            provider.ensureAll(List.of("ca"), "ca");
            assertThat(stored.keySet()).containsExactlyInAnyOrderElementsOf(MessageTemplateSeed.eligible().stream().map(s -> s.code()).toList());
            reset(templates);
            when(templates.findAll()).thenReturn(List.of());
            when(templates.insert(any(MessageTemplate.class))).thenThrow(new IllegalStateException("disk full"));
            assertThatThrownBy(() -> provider.ensureAll(List.of("ca"), "ca")).isInstanceOf(IllegalStateException.class);
        }
    }
}
