package com.agilityhub.core.identity.application;

import com.agilityhub.core.platform.application.definition.ClubDefinitionWriter;
import com.agilityhub.core.platform.application.definition.ClubDefinitions;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link SeedTestAccountsCommand} (CLI `bin/core identity:seed-test-accounts`): the command's name and the
 * dry-run report it prints. Collaborators are mocks; fictional data.
 */
class SeedTestAccountsCommandSurvivorsTest {
    final ClubDefinitions definitions = mock(ClubDefinitions.class);
    final SeedTestAccountsCommand command = new SeedTestAccountsCommand(definitions);

    @Test void E11_T06_theCommandIsNamedIdentitySeedTestAccounts() {
        assertThat(command.name()).isEqualTo("identity:seed-test-accounts");
    }

    @Test void E11_T06_aDryRunPrintsTheAccountsReport() {
        // The accounts-only plan's single line and summary (ClubDefinitionWriter.java:119-121 via planAccounts :205-206).
        var result = new ClubDefinitionWriter.Result("club-a", List.of("+ accounts: 1 to provision"), Map.of("accounts", 1));
        when(definitions.applyAccounts(Path.of("seeds/club-example.yaml"), true, false)).thenReturn(result);
        var out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            command.run(new DefaultApplicationArguments("seeds/club-example.yaml", "--dry-run"));
        } finally { System.setOut(original); }

        assertThat(out.toString(StandardCharsets.UTF_8)).contains("+ accounts: 1 to provision").contains("1 changes (dry run)");
    }
}
