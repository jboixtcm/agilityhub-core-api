package com.agilityhub.core.platform.application.audit;

import com.agilityhub.core.platform.persistence.audit.AuditEntry;
import com.agilityhub.core.platform.persistence.audit.AuditRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * S17 R-17-10 (AUTHZ-05, owner decision 13-11-2026): a write made with a support access that its service does not audit is
 * audited `SUPPORT_WRITE` on the access's grant, as the AgilityHub team's (`origin = PLATFORM_SUPPORT`, `actorRole = PLATFORM`),
 * naming the request; a write that its service audited already keeps that entry alone.
 */
class SupportWriteAuditTest {
    static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");
    final AuditRepository repository = mock(AuditRepository.class);
    final AuditActorProvider actors = () -> new AuditActor("platform-a", "Pat Support", "PLATFORM", null, true, "203.0.113.7", "fixture", "trace-a");
    final SupportWriteAudit audit = new SupportWriteAudit(repository, new AuditWriter(repository, actors, Clock.fixed(NOW, ZoneOffset.UTC)),
            actors, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test void AUTHZ_05_anUnauditedSupportWriteIsAuditedOnItsGrantAsThePlatformTeams() {
        assertThat(audit.started()).isEqualTo(NOW);
        assertThat(audit.record("grant-a", NOW, "POST", "/api/v1/saved-views", "/api/v1/saved-views", 201)).isTrue();
        verify(repository).supportEntrySince("trace-a", NOW);
        var entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(repository).append(entry.capture());
        var written = entry.getValue();
        assertThat(written.action()).isEqualTo(AuditAction.SUPPORT_WRITE);
        assertThat(written.entityType()).isEqualTo("ImpersonationGrant");
        assertThat(written.entityId()).isEqualTo("grant-a");
        assertThat(written.origin()).isEqualTo("PLATFORM_SUPPORT");
        assertThat(written.actorRole()).isEqualTo("PLATFORM");
        assertThat(written.actorAccountId()).isEqualTo("platform-a");
        assertThat(written.support()).isTrue();
        assertThat(written.traceId()).isEqualTo("trace-a");
        assertThat(written.changes()).isEmpty();
        assertThat(written.memberId()).isNull();
        assertThat(written.details()).isEqualTo(Map.of("method", "POST", "route", "/api/v1/saved-views", "path", "/api/v1/saved-views", "status", 201));
        // An event action: it records that the write happened, with no field diff and no reason (R-14-10).
        assertThat(AuditWriter.EVENT_ACTIONS).contains(AuditAction.SUPPORT_WRITE);
    }

    @Test void AUTHZ_05_aSupportWriteItsServiceAuditedKeepsItsOwnEntryAlone() {
        when(repository.supportEntrySince("trace-a", NOW)).thenReturn(true);
        assertThat(audit.record("grant-a", NOW, "PUT", "/api/v1/parameters/{key}", "/api/v1/parameters/bookings.lateCancelThresholdMinutes", 200)).isFalse();
        verify(repository, never()).append(any());
    }

    @Test void AUTHZ_05_aRouteSpringDidNotNameIsLeftOutOfTheDetails() {
        assertThat(audit.record("grant-a", NOW, "DELETE", null, "/api/v1/saved-views/view-a", 204)).isTrue();
        var entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(repository).append(entry.capture());
        assertThat(entry.getValue().details()).containsOnlyKeys("method", "path", "status").containsEntry("status", 204);
    }
}
