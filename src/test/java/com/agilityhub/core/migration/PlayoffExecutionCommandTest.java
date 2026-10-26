package com.agilityhub.core.migration;

import com.agilityhub.core.migration.application.*;
import com.agilityhub.core.migration.domain.*;
import com.agilityhub.core.platform.application.MigrationClubAccess;
import com.agilityhub.core.shared.domain.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlayoffExecutionCommandTest {
    @TempDir Path temporary;
    final PlayoffImportService importer=mock(PlayoffImportService.class);
    final MigrationClubAccess clubs=mock(MigrationClubAccess.class);
    final MigrationResetService reset=mock(MigrationResetService.class);
    final MockEnvironment environment=new MockEnvironment();
    final PlayoffCommand command=new PlayoffCommand(importer,clubs,environment,reset);

    @BeforeEach void prepare() {
        when(clubs.resolve(anyString())).thenReturn("club-id");
        when(importer.importDirectory(any(),any(),anyString(),anyBoolean(),anyBoolean(),anyBoolean(),any(),any()))
                .thenReturn(new MigrationReport(true,List.of()));
    }
    @Test void T_18_13_selectionIsNormalizedAndUsesTheExplicitExecutionPath() {
        command.run(new DefaultApplicationArguments("--in=input","--club=sample","--dry-run","--steps=3,5-6"));
        var captured=ArgumentCaptor.forClass(MigrationExecutionRequest.class);
        verify(importer).importDirectory(eq(Path.of("input")),any(),eq("club-id"),eq(true),eq(false),eq(false),isNull(),captured.capture());
        assertThat(captured.getValue().steps().values()).containsExactly(1,3,5,6);
        assertThat(captured.getValue().staged()).isTrue();
        assertThat(captured.getValue().resumeRunId()).isNull();
        assertThat(captured.getValue().approval()).isNull();
        verifyNoMoreInteractions(importer);
    }
    @Test void T_18_13_resumeNamesThePriorRunAndTheSameCutover() {
        command.run(new DefaultApplicationArguments("--in=input","--steps=4-10","--resume-run=prior-run","--cut-over=2026-11-01","--dry-run"));
        var captured=ArgumentCaptor.forClass(MigrationExecutionRequest.class);
        verify(importer).importDirectory(any(),any(),eq("club-id"),eq(true),eq(false),eq(false),eq(LocalDate.of(2026,11,1)),captured.capture());
        assertThat(captured.getValue().resumeRunId()).isEqualTo("prior-run");
        assertThat(captured.getValue().steps().values()).containsExactly(1,4,5,6,7,8,9,10);
    }
    @Test void T_18_14_reapplyNeedsThePrivateWrittenConfirmationAndProductionScope() throws Exception {
        Path approval=approval();
        command.run(new DefaultApplicationArguments("--in=input","--allow-reapply","--reapply-confirmation="+approval,
                "--env=production","--confirm-production","--cut-over=2026-11-01"));
        var captured=ArgumentCaptor.forClass(MigrationExecutionRequest.class);
        verify(importer).importDirectory(any(),any(),eq("club-id"),eq(false),eq(true),eq(true),eq(LocalDate.of(2026,11,1)),captured.capture());
        assertThat(captured.getValue().steps()).isEqualTo(MigrationSteps.all());
        assertThat(captured.getValue().staged()).isTrue();
        assertThat(captured.getValue().approval()).isEqualTo(ReapplyApproval.read(approval));
    }
    @Test void T_18_14_anActiveProductionProfileStillRequiresTheProductionCutover() {
        environment.setActiveProfiles("prod");
        assertThatThrownBy(()->command.run(new DefaultApplicationArguments("--in=input","--steps=1-3","--env=staging","--confirm-production")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--cut-over");
        verifyNoInteractions(importer);
    }
    @Test void T_18_13_invalidSelectorsAndUnsafeCombinationsNeverReachTheImporter() {
        var cases=new ArrayList<List<String>>();
        for (String value:List.of("","0","11","3-1","1,,3","1-2-3","1, 2")) { cases.add(List.of("--steps="+value)); }
        cases.addAll(List.of(List.of("--steps"),List.of("--steps=1","--steps=2"),
                List.of("--resume-run=previous"),List.of("--steps=4-10","--resume-run=previous"),
                List.of("--steps=4-10","--resume-run="),List.of("--allow-reapply"),
                List.of("--allow-reapply=true"),List.of("--reapply-confirmation=missing"),
                List.of("--allow-reapply","--reapply-confirmation=missing"),
                List.of("--allow-reapply","--reapply-confirmation=missing","--env=production","--resume-run=previous","--steps=4-10"),
                List.of("--steps=1-3","--cut-over=not-a-date")));
        for (var options:cases) {
            var args=new ArrayList<>(List.of("--in=input","--dry-run")); args.addAll(options);
            assertThatThrownBy(()->command.run(new DefaultApplicationArguments(args.toArray(String[]::new))))
                    .as(options.toString()).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(importer,reset);
    }
    @Test void T_18_16_resetCannotBeCombinedWithSelectiveOrApprovedExecution() {
        for (var options:List.of(List.of("--steps=1-3"),List.of("--steps=4-10","--resume-run=prior"),
                List.of("--allow-reapply","--reapply-confirmation=missing","--env=production"))) {
            var args=new ArrayList<>(List.of("--reset","--club=sample")); args.addAll(options);
            assertThatThrownBy(()->command.run(new DefaultApplicationArguments(args.toArray(String[]::new))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(importer,reset);
    }
    @Test void T_18_14_invalidConfirmationIsRejectedBeforeTheImport() throws Exception {
        Path approval=approval(); Files.writeString(approval,"{}");
        assertThatThrownBy(()->command.run(new DefaultApplicationArguments("--in=input","--allow-reapply","--reapply-confirmation="+approval,
                "--env=production","--confirm-production","--cut-over=2026-11-01"))).isInstanceOf(ApiException.class);
        verifyNoInteractions(importer);
    }
    private Path approval() throws Exception {
        Path path=temporary.resolve("approval.json");
        Files.writeString(path,"""
                {"version":1,"clubId":"club-id","priorRunId":"prior-run","inputManifestSha256":"%s",
                 "cutover":"2026-11-01","steps":[1,2,3,4,5,6,7,8,9,10],"approvedBy":"fictional-operator",
                 "approvedAt":"2026-10-23T12:00:00Z","reason":"Reviewed fixture correction","runbookReference":"local-test-1"}
                """.formatted("a".repeat(64)));
        Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));
        return path;
    }
}
