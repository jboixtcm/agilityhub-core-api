package com.agilityhub.core.platform.application.audit;

import com.agilityhub.core.platform.persistence.audit.AuditEntry;
import com.agilityhub.core.platform.persistence.audit.AuditRepository;
import java.time.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AuditWriterTest {
    @Test void T_14_09_round2_point5_webhookProvenanceOverridesCallerAndRestoresScope() {
        var repository = mock(AuditRepository.class);
        var writer = new AuditWriter(repository, () -> new AuditActor("account-a", "Example Admin", "ADMIN", "member-a", true, "127.0.0.1", "fixture", null),
                Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC));
        assertThatThrownBy(() -> WebhookAuditContext.open(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookAuditContext.open(" ")).isInstanceOf(IllegalArgumentException.class);
        try (var outer = WebhookAuditContext.open("evt_outer")) {
            assertThatThrownBy(() -> {
                try (var inner = WebhookAuditContext.open("evt_inner")) {
                    writer.write(AuditAction.PAYMENT_REFUNDED, "Invoice", "invoice-a", null, "Correction", List.of());
                    throw new IllegalStateException("Rollback");
                }
            }).hasMessage("Rollback");
            assertThat(WebhookAuditContext.eventId()).isEqualTo("evt_outer");
        }
        assertThat(WebhookAuditContext.eventId()).isNull();
        writer.write(AuditAction.PAYMENT_REFUNDED, "Invoice", "invoice-b", null, "Correction", List.of());
        var entries = ArgumentCaptor.forClass(AuditEntry.class);
        verify(repository, times(2)).append(entries.capture());
        var webhook = entries.getAllValues().getFirst();
        assertThat(webhook.actorRole()).isEqualTo("WEBHOOK"); assertThat(webhook.origin()).isEqualTo("WEBHOOK");
        assertThat(webhook.actorAccountId()).isNull(); assertThat(webhook.actorName()).isNull();
        assertThat(webhook.impersonatedMemberId()).isNull(); assertThat(webhook.support()).isNull();
        assertThat(webhook.ip()).isNull(); assertThat(webhook.userAgent()).isNull();
        assertThat(webhook.traceId()).isNotBlank(); assertThat(webhook.details()).containsEntry("eventId", "evt_inner");
        assertThat(entries.getAllValues().get(1).actorRole()).isEqualTo("ADMIN");
    }
    /**
     * S14 R-14-10 (E5-T28 round 2, review #4): an entry without changes or reason is discarded, except for the «event» actions
     * (`WEEK_VALIDATED`, `DATA_EXPORTED`), which record that the action happened even when no audited field changed.
     */
    @Test void T_14_07_anEntryWithoutChangesIsDiscardedExceptAnEventAction() {
        var repository = mock(AuditRepository.class);
        var writer = new AuditWriter(repository, () -> new AuditActor("account-a", "Example Admin", "ADMIN", null, false, null, null, "trace-a"),
                Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC));
        writer.write(AuditAction.CLUB_UPDATED, "Club", "club-a", null, null, List.of());
        writer.write(AuditAction.MEMBER_UPDATED, "Member", "member-a", "member-a", " ", List.of());
        verifyNoInteractions(repository);
        writer.write(AuditAction.WEEK_VALIDATED, "Week", "week-a", null, null, List.of());
        writer.write(AuditAction.DATA_EXPORTED, "ExportJob", "job-a", null, null, List.of());
        var entries = ArgumentCaptor.forClass(AuditEntry.class);
        verify(repository, times(2)).append(entries.capture());
        assertThat(entries.getAllValues()).extracting(AuditEntry::action).containsExactly(AuditAction.WEEK_VALIDATED, AuditAction.DATA_EXPORTED);
        assertThat(entries.getAllValues()).allSatisfy(entry -> { assertThat(entry.changes()).isEmpty(); assertThat(entry.actorAccountId()).isEqualTo("account-a"); });
    }

    /**
     * E7-T06 round 2, as ruling E83 clarifies E81 (E7-T04 round 3): a system process audits its own change like the other
     * system entries — no account, `actorName = null`, role and origin `SYSTEM` — with the process in `details.job`, never the
     * provider's actor; an unchanged entity still writes nothing. Before the fix `actorName` was the process and there were no
     * details.
     */
    @Test void E7_T06_aSystemProcessIsTheActorOfItsOwnChange() {
        var repository = mock(AuditRepository.class); var provider = mock(AuditActorProvider.class);
        var writer = new AuditWriter(repository, provider, Clock.fixed(Instant.parse("2026-10-01T06:00:00Z"), ZoneOffset.UTC));
        writer.writeAsSystem(new AuditCommand(AuditAction.CATALOG_CHANGED, "MessageTemplate", "template-a", null, Map.of("title", "a"),
                Map.of("title", "a"), null), "template-upgrade");
        verifyNoInteractions(repository);
        writer.writeAsSystem(new AuditCommand(AuditAction.CATALOG_CHANGED, "MessageTemplate", "template-a", null, Map.of("title", "a"),
                Map.of("title", "b"), null), "template-upgrade");
        var entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(repository).append(entry.capture());
        assertThat(entry.getValue().actorName()).isNull(); assertThat(entry.getValue().actorRole()).isEqualTo("SYSTEM");
        assertThat(entry.getValue().actorAccountId()).isNull(); assertThat(entry.getValue().origin()).isEqualTo("SYSTEM");
        assertThat(entry.getValue().details()).isEqualTo(Map.of("job", "template-upgrade"));
        assertThat(entry.getValue().changes()).hasSize(1); assertThat(entry.getValue().traceId()).isNotBlank();
        verifyNoInteractions(provider);
        assertThatThrownBy(() -> writer.writeAsSystem(new AuditCommand(AuditAction.CATALOG_CHANGED, "MessageTemplate", "template-a",
                null, null, null, null), " ")).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * E7-T07 (S14 §3, R-14-10): an announcement's entry has no changes (its template does not change) and is still written,
     * with the batch in `details`; an empty map writes no details. Before the fix the entry was discarded without changes.
     */
    @Test void E7_T07_anAnnouncementIsAnEventActionWithItsBatchInDetails() {
        var repository = mock(AuditRepository.class);
        var writer = new AuditWriter(repository, () -> new AuditActor("account-a", "Example Admin", "ADMIN", null, false, null, null, "trace-a"),
                Clock.fixed(Instant.parse("2026-10-01T06:00:00Z"), ZoneOffset.UTC));
        var details = new java.util.LinkedHashMap<String, Object>();
        details.put("batchId", "batch-a"); details.put("recipientCount", 10); details.put("filters", List.of("status:eq:ACTIVE")); details.put("selection", "FILTERS");
        writer.write(AuditAction.ANNOUNCEMENT_SENT, "MessageTemplate", "template-a", null, null, List.of(), details);
        writer.write(AuditAction.ANNOUNCEMENT_SENT, "MessageTemplate", "template-b", null, null, List.of(), Map.of());
        writer.write(AuditAction.CATALOG_CHANGED, "MessageTemplate", "template-c", null, null, List.of(), details);
        var entries = ArgumentCaptor.forClass(AuditEntry.class);
        verify(repository, times(2)).append(entries.capture());
        var first = entries.getAllValues().getFirst();
        assertThat(first.entityId()).isEqualTo("template-a"); assertThat(first.changes()).isEmpty(); assertThat(first.actorAccountId()).isEqualTo("account-a");
        assertThat(first.details()).containsExactly(Map.entry("batchId", "batch-a"), Map.entry("recipientCount", 10),
                Map.entry("filters", List.of("status:eq:ACTIVE")), Map.entry("selection", "FILTERS"));
        assertThat(entries.getAllValues().get(1).entityId()).isEqualTo("template-b"); assertThat(entries.getAllValues().get(1).details()).isNull();
    }

    /** A request's entry written without details has none (ruling E83: never the system process's `job`). */
    @Test void E7_T06_aRequestsEntryHasNoDetails() {
        var repository = mock(AuditRepository.class);
        var writer = new AuditWriter(repository, () -> new AuditActor("account-a", "Example Admin", "ADMIN", null, false, null, null, "trace-a"),
                Clock.fixed(Instant.parse("2026-10-01T06:00:00Z"), ZoneOffset.UTC));
        writer.write(new AuditCommand(AuditAction.CATALOG_CHANGED, "MessageTemplate", "template-a", null, Map.of("title", "a"), Map.of("title", "b"), null));
        var entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(repository).append(entry.capture());
        assertThat(entry.getValue().details()).isNull(); assertThat(entry.getValue().actorName()).isEqualTo("Example Admin");
    }
}
