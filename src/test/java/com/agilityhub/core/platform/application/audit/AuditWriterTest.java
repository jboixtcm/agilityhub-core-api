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

    /** A request's entry has no `details` (only a system process writes them, ruling E83). */
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
