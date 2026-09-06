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
    @Test void T_17_01_dispatchesApplyAndExportWithExitCodes() {
        var definitions = mock(ClubDefinitions.class); var codec = mock(ClubDefinitionCodec.class);
        var cli = new CoreCli(List.of(new ClubApplyCommand(definitions), new ClubExportCommand(definitions, codec)));
        when(definitions.apply(Path.of("seed.yaml"), true)).thenReturn(new ClubDefinitionWriter.Result("club", List.of("+ club"), Map.of("club", "added")));
        when(definitions.apply(Path.of("seed.yaml"), false)).thenReturn(new ClubDefinitionWriter.Result("club", List.of("= club"), Map.of()));
        assertThat(cli.execute(new DefaultApplicationArguments("--core.command=club:apply", "seed.yaml", "--dry-run"))).isZero();
        assertThat(cli.execute(new DefaultApplicationArguments("--core.command=club:apply", "seed.yaml"))).isZero();
        var export = new ObjectMapper().createObjectNode(); when(definitions.export("club")).thenReturn(export); when(codec.write(export)).thenReturn("apiVersion: agilityhub.club/v1\n");
        assertThat(cli.execute(new DefaultApplicationArguments("--core.command=club:export", "club"))).isZero();
        for (String[] args : new String[][]{{}, {"--core.command="}, {"--core.command=x"}, {"--core.command=x", "--core.command=y"},
                {"--core.command=club:apply"}, {"--core.command=club:apply", "seed.yaml", "--bad"},
                {"--core.command=club:apply", "seed.yaml", "--dry-run=false"}, {"--core.command=club:export"},
                {"--core.command=club:export", "club", "--bad"}}) {
            assertThat(cli.execute(new DefaultApplicationArguments(args))).isEqualTo(1);
        }
        when(definitions.export("club")).thenThrow(new ApiException(ErrorCode.CLUB_NOT_FOUND));
        assertThat(cli.execute(new DefaultApplicationArguments("--core.command=club:export", "club"))).isEqualTo(1);
        doThrow(new IllegalStateException("private database value")).when(definitions).export("club");
        assertThat(cli.execute(new DefaultApplicationArguments("--core.command=club:export", "club"))).isEqualTo(1);
    }
}
