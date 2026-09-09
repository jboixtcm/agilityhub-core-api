package com.agilityhub.core.cli;

import com.agilityhub.core.platform.application.definition.*;
import com.agilityhub.core.shared.application.CoreCommand;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoreCliTest {
    @Test void T_17_01_seedAliasAndApplyForwardOnlyExplicitPasswordOverrides() {
        var definitions = mock(ClubDefinitions.class);
        var result = new ClubDefinitionWriter.Result("club", List.of(), Map.of());
        when(definitions.apply(any(Path.class), anyBoolean(), anyBoolean())).thenReturn(result);
        when(definitions.applyAccounts(any(Path.class), anyBoolean(), anyBoolean())).thenReturn(result);
        var cli = new CoreCli(List.of(new ClubApplyCommand(definitions), new com.agilityhub.core.identity.application.SeedTestAccountsCommand(definitions)));
        assertThat(cli.execute(new DefaultApplicationArguments("--core.command=club:apply", "seed.yaml", "--allow-seed-passwords"))).isZero();
        verify(definitions).apply(Path.of("seed.yaml"), false, true);
        assertThat(cli.execute(new DefaultApplicationArguments("--core.command=identity:seed-test-accounts", "--club=canic", "--dry-run", "--allow-seed-passwords"))).isZero();
        verify(definitions).applyAccounts(Path.of("seeds/club-canic.yaml"), true, true);
        assertThat(cli.execute(new DefaultApplicationArguments("--core.command=identity:seed-test-accounts", "seed.yaml"))).isZero();
        verify(definitions).applyAccounts(Path.of("seed.yaml"), false, false);
        for (String[] args : new String[][]{{}, {"--club"}, {"--club=../outside"}, {"--club=canic", "seed.yaml"},
                {"--club=canic", "--club=minim"}, {"--club=canic", "--bad"}, {"seed.yaml", "--allow-seed-passwords=false"}, {"seed.yaml", "--dry-run=false"}}) {
            var all = new java.util.ArrayList<>(List.of("--core.command=identity:seed-test-accounts")); all.addAll(List.of(args));
            assertThat(cli.execute(new DefaultApplicationArguments(all.toArray(String[]::new)))).isEqualTo(1);
        }
    }
    @Test void T_17_01_dispatchesApplyAndExportWithExitCodes() {
        var definitions = mock(ClubDefinitions.class); var codec = mock(ClubDefinitionCodec.class);
        var cli = new CoreCli(List.of(new ClubApplyCommand(definitions), new ClubExportCommand(definitions, codec)));
        when(definitions.apply(Path.of("seed.yaml"), true, false)).thenReturn(new ClubDefinitionWriter.Result("club", List.of("+ club"), Map.of("club", "added")));
        when(definitions.apply(Path.of("seed.yaml"), false, false)).thenReturn(new ClubDefinitionWriter.Result("club", List.of("= club"), Map.of()));
        assertThat(cli.execute(new DefaultApplicationArguments("--core.command=club:apply", "seed.yaml", "--dry-run"))).isZero();
        assertThat(cli.execute(new DefaultApplicationArguments("--core.command=club:apply", "seed.yaml"))).isZero();
        var export = new ObjectMapper().createObjectNode(); when(definitions.export("club")).thenReturn(export); when(codec.write(export)).thenReturn("apiVersion: agilityhub.club/v1\n");
        assertThat(cli.execute(new DefaultApplicationArguments("--core.command=club:export", "club"))).isZero();
        for (String[] args : new String[][]{{}, {"--core.command="}, {"--core.command=x"}, {"--core.command=x", "--core.command=y"},
                {"--core.command=club:apply"}, {"--core.command=club:apply", "seed.yaml", "--bad"},
                {"--core.command=club:apply", "seed.yaml", "--allow-seed-passwords=false"}, {"--core.command=club:apply", "seed.yaml", "--dry-run=false"}, {"--core.command=club:export"},
                {"--core.command=club:export", "club", "--bad"}}) {
            assertThat(cli.execute(new DefaultApplicationArguments(args))).isEqualTo(1);
        }
        when(definitions.export("club")).thenThrow(new ApiException(ErrorCode.CLUB_NOT_FOUND));
        assertThat(cli.execute(new DefaultApplicationArguments("--core.command=club:export", "club"))).isEqualTo(1);
        doThrow(new IllegalStateException("private database value")).when(definitions).export("club");
        assertThat(cli.execute(new DefaultApplicationArguments("--core.command=club:export", "club"))).isEqualTo(1);
    }
}
