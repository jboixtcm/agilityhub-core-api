package com.agilityhub.core.migration;

import com.agilityhub.core.migration.application.*;
import com.agilityhub.core.platform.application.MigrationClubAccess;
import com.agilityhub.core.shared.application.TenantContext;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MigrationReportCommandTest {
    @TempDir Path directory;
    final MigrationReports reports=mock(MigrationReports.class); final MigrationClubAccess clubs=mock(MigrationClubAccess.class);
    final MigrationReportCommand command=new MigrationReportCommand(reports,clubs);
    @AfterEach void clear() { TenantContext.clear(); }
    @Test void T_18_16_recoveryParsesOnlyPrivateOriginalInputsInsideExplicitTenant() throws Exception {
        try(var files=Files.list(Path.of("src/test/resources/fixtures/playoff"))) {
            for(var file:files.filter(Files::isRegularFile).toList()) { Files.copy(file,directory.resolve(file.getFileName())); }
        }
        when(clubs.resolve("demo")).thenReturn("demo-id");
        doAnswer(invocation->{
            assertThat(TenantContext.require()).isEqualTo("demo-id");
            PlayoffInput input=invocation.getArgument(1); assertThat(input.manifest().mappingSha256()).matches("[0-9a-f]{64}");
            return null;
        }).when(reports).publish(eq("run-1"),any());
        assertThat(command.name()).isEqualTo("migration:report");
        command.run(new DefaultApplicationArguments("--club=demo","--run=run-1","--in="+directory));
        verify(reports).publish(eq("run-1"),any()); assertThat(TenantContext.current()).isNull();
    }
    @Test void T_18_16_recoveryRejectsImportFlagsOrMissingRunRatherThanApplying() {
        assertThatThrownBy(()->command.run(new DefaultApplicationArguments("--club=demo","--run=run-1","--in="+directory,"--apply")))
                .isInstanceOf(IllegalArgumentException.class);
        when(clubs.resolve("demo")).thenReturn("demo-id");
        assertThatThrownBy(()->command.run(new DefaultApplicationArguments("--club=demo","--in="+directory)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(reports);
    }
}
