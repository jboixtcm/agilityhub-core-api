package com.agilityhub.core.identity.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import static com.agilityhub.core.identity.application.LearnImportReport.Outcome.*;
import static com.agilityhub.core.identity.application.LearnImportReport.Reason.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link ImportLearnCommand#run} (S01 R-01-12, T-01-14): a real import hands its report to the pretty
 * JSON writer for `target/import-learn-report.json` (ImportLearnCommand.java:36-37), and a wrong invocation fails with the usage
 * message. The importer and the JSON writer are mocks, so the test writes, moves and deletes no file: a real report file at a
 * fixed path would race with ImportLearnCommandTest and with the parallel PIT workers (pom.xml `threads` 4), and could destroy an
 * operator's report. The command itself only ensures `target/` exists (ImportLearnCommand.java:36).
 */
class ImportLearnCommandSurvivorsTest {
    final LearnImportService importer = mock(LearnImportService.class);
    final ObjectMapper mapper = mock(ObjectMapper.class);
    final ObjectWriter writer = mock(ObjectWriter.class);
    final ImportLearnCommand command = new ImportLearnCommand(importer, mapper);

    @Test void T_01_14_aRealImportWritesItsJsonReport() throws Exception {
        // LearnImportService.java:93-95: the first data row (line 2) of a new Learn user creates its account.
        var report = LearnImportReport.of(false, List.of(new LearnImportReport.Entry(2, CREATED, NEW_ACCOUNT)));
        when(importer.importFile(Path.of("learn-users.csv"), false, Set.of())).thenReturn(report);
        when(mapper.writerWithDefaultPrettyPrinter()).thenReturn(writer);

        command.run(new DefaultApplicationArguments("learn-users.csv"));

        verify(writer).writeValue(Path.of("target/import-learn-report.json").toFile(), report);
    }

    @Test void T_01_14_anInvocationWithoutTheCsvPathFailsWithTheUsage() {
        assertThatThrownBy(() -> command.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Usage: identity:import-learn <csv> [--dry-run] [--platform-admins=a@x,b@y]");
        verifyNoInteractions(importer);
    }
}
