package com.agilityhub.core.platform.application.audit;

import com.agilityhub.core.platform.persistence.audit.AuditEntry;
import com.agilityhub.core.platform.persistence.audit.AuditRepository;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AuditWriterTest {
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
     * E7-T06 round 2 (ruling E81): a system process audits its own change — the actor is the process (`actorName`, role
     * `SYSTEM`, no account, origin `SYSTEM`), never the provider's — and an unchanged entity still writes nothing.
     */
    @Test void E7_T06_aSystemProcessIsTheActorOfItsOwnChange() {
        var repository = mock(AuditRepository.class); var provider = mock(AuditActorProvider.class);
        var writer = new AuditWriter(repository, provider, Clock.fixed(Instant.parse("2026-10-01T06:00:00Z"), ZoneOffset.UTC));
        writer.writeAsSystem(new AuditCommand(AuditAction.CATALOG_CHANGED, "MessageTemplate", "template-a", null, java.util.Map.of("title", "a"),
                java.util.Map.of("title", "a"), null), "system:template-upgrade");
        verifyNoInteractions(repository);
        writer.writeAsSystem(new AuditCommand(AuditAction.CATALOG_CHANGED, "MessageTemplate", "template-a", null, java.util.Map.of("title", "a"),
                java.util.Map.of("title", "b"), null), "system:template-upgrade");
        var entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(repository).append(entry.capture());
        assertThat(entry.getValue().actorName()).isEqualTo("system:template-upgrade"); assertThat(entry.getValue().actorRole()).isEqualTo("SYSTEM");
        assertThat(entry.getValue().actorAccountId()).isNull(); assertThat(entry.getValue().origin()).isEqualTo("SYSTEM");
        assertThat(entry.getValue().changes()).hasSize(1); assertThat(entry.getValue().traceId()).isNotBlank();
        verifyNoInteractions(provider);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> writer.writeAsSystem(new AuditCommand(AuditAction.CATALOG_CHANGED, "MessageTemplate", "template-a",
                null, null, null, null), " ")).isInstanceOf(IllegalArgumentException.class);
    }
}
