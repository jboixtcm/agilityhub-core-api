package com.agilityhub.core.migration;

import com.agilityhub.core.migration.application.*;
import com.agilityhub.core.platform.application.MigrationClubAccess;
import com.agilityhub.core.shared.application.TenantContext;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.boot.DefaultApplicationArguments;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** MIG-13 (owner decision, 13-11-2026): `migration:report-purge --club --run` purges one run's report in that club, idempotently. */
class MigrationReportPurgeCommandTest {
    final MigrationReports reports = mock(MigrationReports.class); final MigrationClubAccess clubs = mock(MigrationClubAccess.class);
    final MigrationReportPurgeCommand command = new MigrationReportPurgeCommand(reports, clubs);
    @AfterEach void clear() { TenantContext.clear(); }
    String printed(String... args) {
        var out = new ByteArrayOutputStream(); var previous = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try { command.run(new DefaultApplicationArguments(args)); } finally { System.setOut(previous); }
        return out.toString(StandardCharsets.UTF_8);
    }
    @Test void MIG_13_purgesTheRunsReportInsideTheClubAndSaysWhenItWasAlreadyPurged() {
        when(clubs.resolve("demo")).thenReturn("demo-id");
        when(reports.purgeNow("run-1")).thenAnswer(call -> { assertThat(TenantContext.require()).isEqualTo("demo-id"); return true; }).thenReturn(false);
        assertThat(command.name()).isEqualTo("migration:report-purge");
        assertThat(printed("--club=demo", "--run=run-1")).isEqualTo("Migration report purged for run run-1" + System.lineSeparator());
        assertThat(printed("--club=demo", "--run=run-1")).isEqualTo("Migration report of run run-1 was already purged" + System.lineSeparator());
        verify(reports, times(2)).purgeNow("run-1"); assertThat(TenantContext.current()).isNull();
    }
    @Test void MIG_13_invalidOptionsPurgeNothing() {
        for (var args : List.of(new String[]{}, new String[]{"--club=demo"}, new String[]{"--run=run-1"}, new String[]{"--club=demo", "--run="},
                new String[]{"--club=demo", "--run=a", "--run=b"}, new String[]{"--club=demo", "--run=run-1", "--force"}, new String[]{"--club=demo", "run-1"})) {
            assertThatThrownBy(() -> command.run(new DefaultApplicationArguments(args))).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(reports, clubs);
    }
}
