package com.agilityhub.core.migration;

import com.agilityhub.core.migration.application.*;
import com.agilityhub.core.migration.domain.*;
import com.agilityhub.core.migration.persistence.*;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MigrationReportsTest {
    static final Instant NOW=Instant.parse("2026-10-23T10:00:00Z");
    final MigrationRunRepository runs=mock(MigrationRunRepository.class);
    final MigrationReportStore store=mock(MigrationReportStore.class);
    final ClubConfigService configs=mock(ClubConfigService.class);
    final ClubConfig config=mock(ClubConfig.class);
    final com.agilityhub.core.platform.application.audit.AuditWriter audit=mock(com.agilityhub.core.platform.application.audit.AuditWriter.class);
    final MigrationReports reports=new MigrationReports(runs,store,new MigrationReportWorkbook(),configs,Clock.fixed(NOW,ZoneOffset.UTC),audit);
    final InputManifest manifest=new InputManifest(List.of(new InputManifest.File("members.csv","a".repeat(64),1)));
    final PlayoffInput input=new PlayoffInput(Map.of("members",List.of(new PlayoffInput.Row("members",2,Map.of(
            "id","fictional-1","firstName","=Fictional","surname","Person","email","fictional@example.test","iban","NOT-A-BANK-ACCOUNT")))),List.of(),manifest);
    MigrationRun run(Instant expires,String key) {
        return new MigrationRun("run-1","club-1","PLAYOFF","APPLY","PRODUCTION",2,"COMPLETED",NOW.minusSeconds(60),NOW,
                Map.of("membersCREATED",1L),manifest,List.of(new MigrationReport.Entry("members",2,"members","WARNING","EMAIL_SHARED","email")),key,expires,null);
    }
    @BeforeEach void setup() { TenantContext.open("club-1"); when(configs.current("club-1")).thenReturn(config); when(config.get("migration.exportRetentionDays",Integer.class)).thenReturn(30); }
    @AfterEach void clear() { TenantContext.clear(); }
    @Test void T_18_16_reportContainsNamesAsTextWithoutContactsAndDeletesPrivateSpool() throws Exception {
        var run=run(NOW.plus(Duration.ofDays(30)),null); when(runs.findById("run-1")).thenReturn(Optional.of(run));
        when(runs.reportPublished(anyString(),anyString(),any())).thenReturn(true);
        doAnswer(invocation->{
            Path path=invocation.getArgument(1);
            assertThat(Files.getPosixFilePermissions(path)).isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            try(var stream=Files.newInputStream(path);var workbook=WorkbookFactory.create(stream)) {
                var row=workbook.getSheet("Resultats").getRow(1);
                assertThat(workbook.getSheet("Resum").getRow(0).getCell(0).getStringCellValue()).isEqualTo("Camp");
                assertThat(workbook.getSheet("Resultats").getRow(0).getCell(7).getStringCellValue()).isEqualTo("Nom");
                assertThat(row.getCell(7).getCellType()).isEqualTo(CellType.STRING);
                assertThat(row.getCell(7).getStringCellValue()).isEqualTo("=Fictional Person");
                var text=new StringBuilder(); for(var sheet:workbook) { for(var entry:sheet) { for(var cell:entry) { text.append(cell); } } }
                assertThat(text.toString()).contains("membersCREATED","EMAIL_SHARED","fictional-1").doesNotContain("fictional@example.test","NOT-A-BANK-ACCOUNT");
            }
            return null;
        }).when(store).put(anyString(),any(),any());
        reports.publish("run-1",input);
        var path=ArgumentCaptor.forClass(Path.class); verify(store).put(eq("migration-reports/club-1/run-1/report.xlsx"),path.capture(),eq(run.reportExpiresAt()));
        assertThat(path.getValue()).doesNotExist();
        verify(runs).reportPublished("run-1","migration-reports/club-1/run-1/report.xlsx",NOW);
    }
    @Test void T_18_16_changedInputCannotRebuildAnExistingRun() {
        when(runs.findById("run-1")).thenReturn(Optional.of(run(NOW.plusSeconds(1),null)));
        assertThatThrownBy(()->reports.publish("run-1",new PlayoffInput(input.files(),List.of())))
                .isInstanceOfSatisfying(ApiException.class,error->assertThat(error.code()).isEqualTo(ErrorCode.INPUT_SCHEMA_MISMATCH));
        verifyNoInteractions(store);
    }
    @Test void T_18_16_independentReceiptAndPackIdsNeverAcquireAnUnrelatedMemberName() throws Exception {
        var files=new HashMap<>(input.files());
        for(var adapter:List.of("plans","levels","groups","receipts","packs")) {
            files.put(adapter,List.of(new PlayoffInput.Row(adapter,2,Map.of("id","fictional-1"))));
        }
        var rows=List.of("plans","levels","groups","receipts","packs").stream()
                .map(adapter->new MigrationReport.Entry(adapter,2,adapter,"ERROR","INPUT_SCHEMA_MISMATCH")).toList();
        var reportRun=new MigrationRun("run-1","club-1","PLAYOFF","APPLY","PRODUCTION",2,"FAILED",NOW,NOW,
                Map.of(),manifest,rows,null,NOW.plus(Duration.ofDays(30)),null);
        var file=new MigrationReportWorkbook().write(reportRun,new PlayoffInput(files,List.of(),manifest));
        try(var stream=Files.newInputStream(file);var workbook=WorkbookFactory.create(stream)) {
            var sheet=workbook.getSheet("Resultats previstos");
            for(int index=1;index<=3;index++) {
                assertThat(sheet.getRow(index).getCell(6).getStringCellValue()).isEqualTo("fictional-1");
                assertThat(sheet.getRow(index).getCell(7).getStringCellValue()).isEqualTo("=Fictional Person");
            }
            for(int index=4;index<=5;index++) {
                assertThat(sheet.getRow(index).getCell(6).getStringCellValue()).isEmpty();
                assertThat(sheet.getRow(index).getCell(7).getStringCellValue()).isEmpty();
            }
        } finally { Files.deleteIfExists(file); }
    }
    @Test void T_18_13_completedPartialReportDoesNotClaimTheWholePipelineFinished() throws Exception {
        var execution=new MigrationExecution(new TreeSet<>(Set.of(1,4)),new TreeSet<>(Set.of(1,4)),new TreeSet<>(Set.of(1,2,3)),
                LocalDate.parse("2026-10-30"),"root-run","prior-run",2,null,true);
        var reportRun=new MigrationRun("run-1","club-1","PLAYOFF","APPLY","PRODUCTION",2,"COMPLETED",NOW,NOW,
                Map.of(),manifest,List.of(),null,NOW.plus(Duration.ofDays(30)),null,execution);
        var file=new MigrationReportWorkbook().write(reportRun,input);
        try(var stream=Files.newInputStream(file);var workbook=WorkbookFactory.create(stream)) {
            var fields=new HashMap<String,String>();
            for(var row:workbook.getSheet("Resum")) { fields.put(row.getCell(0).getStringCellValue(),row.getCell(1).toString()); }
            assertThat(fields).containsEntry("Estat","COMPLETED").containsEntry("Tota la seqüència acabada","No")
                    .containsEntry("Passos acabats en aquesta execució","[1, 4]").containsEntry("Passos acabats abans","[1, 2, 3]")
                    .containsEntry("Execució inicial","root-run").containsEntry("Execució anterior","prior-run");
        } finally { Files.deleteIfExists(file); }
    }
    @Test void T_18_16_uploadFailureNeverPublishesPointerAndCleansSpool() {
        when(runs.findById("run-1")).thenReturn(Optional.of(run(NOW.plusSeconds(1),null)));
        doThrow(new IllegalStateException("fictional storage failure")).when(store).put(anyString(),any(),any());
        assertThatThrownBy(()->reports.publish("run-1",input)).isInstanceOf(IllegalStateException.class);
        var path=ArgumentCaptor.forClass(Path.class); verify(store).put(anyString(),path.capture(),any());
        assertThat(path.getValue()).doesNotExist(); verify(runs,never()).reportPublished(anyString(),anyString(),any());
    }
    @Test void T_18_16_linkExpiresAtThirtyDaysEvenBeforePurgeRuns() {
        when(runs.findById("run-1")).thenReturn(Optional.of(run(NOW,"migration-reports/club-1/run-1/report.xlsx")));
        assertThatThrownBy(()->reports.download("run-1")).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.code()).isEqualTo(ErrorCode.EXPORT_EXPIRED));
        verifyNoInteractions(store);
    }
    @Test void T_18_16_missingUnpublishedOrStagingReportIsNotFoundRatherThanExpired() {
        var legacy=new MigrationRun("run-1","club-1","PLAYOFF","APPLY","STAGING",2,"COMPLETED",NOW,NOW,Map.of());
        for(var unavailable:List.of(legacy,run(NOW.plusSeconds(1),null),run(NOW.minusSeconds(1),null))) {
            when(runs.findById("run-1")).thenReturn(Optional.of(unavailable));
            assertThatThrownBy(()->reports.download("run-1")).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.code()).isEqualTo(ErrorCode.NOT_FOUND));
        }
        verifyNoInteractions(store,audit);
    }
    @Test void T_18_16_urlLifetimeCannotExtendPastReportRetention() {
        var expiry=NOW.plusSeconds(10); when(runs.findById("run-1")).thenReturn(Optional.of(run(expiry,"migration-reports/club-1/run-1/report.xlsx")));
        when(store.downloadUrl(anyString(),any())).thenReturn("https://private.example.test/report");
        assertThat(reports.download("run-1").expiresAt()).isEqualTo(expiry);
        verify(store).downloadUrl("migration-reports/club-1/run-1/report.xlsx",expiry);
    }
    @Test void T_18_16_cleanupRemovesAnUnpublishedOrphanAndRetriesStorageFailure() {
        when(runs.findById("run-1")).thenReturn(Optional.of(run(NOW,null)));
        doThrow(new IllegalStateException("retry")).doNothing().when(store).delete(anyString());
        assertThatThrownBy(()->reports.purge("run-1",NOW)).isInstanceOf(IllegalStateException.class);
        verify(runs,never()).reportPurged(anyString(),any());
        when(runs.reportPurged("run-1",NOW)).thenReturn(true);
        assertThat(reports.purge("run-1",NOW)).isTrue(); verify(store,times(2)).delete("migration-reports/club-1/run-1/report.xlsx");
    }
    @Test void T_18_16_retentionCannotBeSilentlyExtended() {
        when(config.get("migration.exportRetentionDays",Integer.class)).thenReturn(31);
        assertThatThrownBy(reports::requireReady).isInstanceOf(IllegalStateException.class); verifyNoInteractions(store);
    }
}
