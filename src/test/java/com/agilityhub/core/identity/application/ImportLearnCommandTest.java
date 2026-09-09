package com.agilityhub.core.identity.application;

import com.agilityhub.core.cli.CoreCli;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static com.agilityhub.core.identity.application.LearnImportReport.Outcome.*;
import static com.agilityhub.core.identity.application.LearnImportReport.Reason.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class ImportLearnCommandTest {
    private final LearnImportService service = mock(LearnImportService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final CoreCli cli = new CoreCli(List.of(new ImportLearnCommand(service, mapper)));
    private int execute(String... arguments) {
        var args = new java.util.ArrayList<>(List.of("--core.command=identity:import-learn")); args.addAll(List.of(arguments));
        return cli.execute(new DefaultApplicationArguments(args.toArray(String[]::new)));
    }
    @Test void T_01_14_cliPrintsPrivateReportAndDryRunDoesNotWrite(CapturedOutput output) throws Exception {
        var path = Path.of("target/import-learn-report.json");
        var report = LearnImportReport.of(false, List.of(new LearnImportReport.Entry(2, CREATED, NEW_ACCOUNT)));
        when(service.importFile(Path.of("fixture.csv"), false, Set.of("admin@example.test"))).thenReturn(report);
        assertThat(execute("fixture.csv", "--platform-admins=ADMIN@example.test")).isZero();
        assertThat(mapper.readTree(path.toFile()).path("created").asInt()).isEqualTo(1);
        var contents = Files.readString(path); var modified = Files.getLastModifiedTime(path);
        when(service.importFile(Path.of("fixture.csv"), true, Set.of())).thenReturn(LearnImportReport.of(true, report.rows()));
        assertThat(execute("fixture.csv", "--dry-run")).isZero();
        assertThat(Files.readString(path)).isEqualTo(contents); assertThat(Files.getLastModifiedTime(path)).isEqualTo(modified);
        assertThat(output).contains("1 would create").doesNotContain("admin@example.test", "ADMIN@example.test");
    }
    @Test void T_01_14_cliReportsRowErrorsAndRejectsInvalidArguments(CapturedOutput output) {
        when(service.importFile(Path.of("fixture.csv"), false, Set.of())).thenReturn(LearnImportReport.of(false,
                List.of(new LearnImportReport.Entry(2, ERROR, INVALID_PASSWORD))));
        assertThat(execute("fixture.csv")).isEqualTo(1);
        assertThat(output).contains("INVALID_PASSWORD", "VALIDATION_ERROR");
        for (String[] args : new String[][]{{}, {"a", "b"}, {"a", "--unknown"}, {"a", "--dry-run=false"},
                {"a", "--platform-admins"}, {"a", "--platform-admins="}, {"a", "--platform-admins=bad"},
                {"a", "--platform-admins=a@example.test,"}, {"a", "--platform-admins=a@example.test", "--platform-admins=b@example.test"}}) {
            assertThat(execute(args)).isEqualTo(1);
        }
        verify(service, times(1)).importFile(any(), anyBoolean(), any());
    }
}
