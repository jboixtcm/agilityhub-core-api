package com.agilityhub.core.migration;

import com.agilityhub.core.clubs.common.application.*;
import com.agilityhub.core.clubs.common.application.ports.OutlivedBuildSessionsPort;
import com.agilityhub.core.clubs.common.persistence.*;
import com.agilityhub.core.clubs.followup.application.AttachmentStorage;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.jobs.*;
import com.agilityhub.core.shared.application.MigrationReportRetention;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MigrationReportCleanupTest {
    @Test void T_18_16_P9PlansDryRunWithoutDeletingAndAppliesOriginalScheduledDeadline() {
        var at=Instant.parse("2026-11-22T10:00:00Z");
        var reports=mock(MigrationReportRetention.class); var config=mock(ClubConfig.class); var recorder=mock(JobRunRecorder.class);
        when(config.modules()).thenReturn(Set.of()); when(config.get(anyString(),eq(Integer.class))).thenReturn(7);
        when(reports.expired(at)).thenReturn(List.of("expired-run")); when(reports.purge("expired-run",at)).thenReturn(true);
        var job=new CleanupJob(mock(CleanupRepository.class),mock(ListExportRepository.class),mock(ExportStorage.class),mock(AttachmentStorage.class),
                mock(ParameterCatalog.class),mock(OutlivedBuildSessionsPort.class),mock(PlatformDomains.class),reports);
        var dry=new JobContext("club",ZoneOffset.UTC,at,LocalDate.of(2026,11,22),true,config,recorder,"job");
        var planned=job.plan(dry); var report=planned.stream().filter(item->item.entityType().equals("MigrationReport")).findFirst().orElseThrow();
        assertThat(report.entityId()).isEqualTo("expired-run"); verify(reports,never()).purge(anyString(),any());
        verify(recorder).count("WOULD_DELETE_migrationReports",1L);
        var live=new JobContext("club",ZoneOffset.UTC,at,LocalDate.of(2026,11,22),false,config,recorder,"job");
        var effect=job.apply(live,report); assertThat(effect.counters()).containsEntry("migrationReportsPurged",1L);
        verify(reports).purge("expired-run",at);
    }
}
