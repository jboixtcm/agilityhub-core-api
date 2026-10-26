package com.agilityhub.core.migration;

import com.agilityhub.core.migration.application.*;
import com.agilityhub.core.migration.api.MigrationFilesController;
import com.agilityhub.core.migration.persistence.*;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.audit.AuditWriter;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.support.MockClock;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VolumeMigrationReportStoreTest {
    @TempDir Path temporary;
    static final Instant NOW = Instant.parse("2026-10-26T00:00:00Z");
    static final String KEY = "migration-reports/club-a/run-1/report.xlsx";
    final MockClock clock = new MockClock(NOW);
    final MigrationRunRepository runs = mock(MigrationRunRepository.class);
    PrivateFileStorageSettings settings;
    VolumeMigrationReportStore store;
    Path root;
    @BeforeEach void storage() throws Exception {
        TenantContext.clear(); root = temporary.toRealPath().resolve("volume");
        Files.createDirectory(root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path marker = root.resolve(PrivateFileStorageSettings.MARKER);
        Files.writeString(marker, PrivateFileStorageSettings.MARKER_CONTENT);
        Files.setPosixFilePermissions(marker, PosixFilePermissions.fromString("rw-------"));
        var env = new MockEnvironment().withProperty("core.storage.mode", "filesystem").withProperty("core.storage.root", root.toString())
                .withProperty("core.storage.signing-key", Base64.getEncoder().encodeToString(new java.security.SecureRandom().generateSeed(32)))
                .withProperty("core.hosted-demo.enabled", "true").withProperty("email.delivery-enabled", "false");
        env.setActiveProfiles("prod"); settings = PrivateFileStorageSettings.from(env); store = new VolumeMigrationReportStore(settings, clock);
        Path source = temporary.resolve("fixture.xlsx"); Files.writeString(source, "fictional report");
        store.put(KEY, source, NOW.plus(Duration.ofDays(30)));
    }
    @AfterEach void clear() { TenantContext.clear(); }
    MigrationRun run(String key, Instant expires, Instant purged) {
        return new MigrationRun("run-1", "club-a", "PLAYOFF", "APPLY", "PRODUCTION", 2, "COMPLETED", NOW, NOW,
                Map.of(), null, List.of(), key, expires, purged);
    }
    MigrationReports reports() {
        return new MigrationReports(runs, store, mock(MigrationReportWorkbook.class), mock(ClubConfigService.class), clock, mock(AuditWriter.class));
    }
    String signature(String url) { return url.substring(url.indexOf("signature=") + 10); }
    @Test void T_18_16_volumeReportsHavePrivateBytesRestartStableSignaturesAndFiveMinuteLinks() throws Exception {
        Path report = root.resolve(KEY);
        assertThat(Files.getPosixFilePermissions(report)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        String url = store.downloadUrl(KEY, NOW.plus(Duration.ofDays(30)));
        assertThat(url).startsWith("/api/v1/migrations/files/club-a/run-1?").contains("expires=" + NOW.plusSeconds(300).getEpochSecond());
        var restarted = new VolumeMigrationReportStore(settings, clock);
        restarted.authorize(KEY, NOW.plusSeconds(300).getEpochSecond(), signature(url));
        try (var input = restarted.open(KEY)) { assertThat(input.readAllBytes()).asString().isEqualTo("fictional report"); }
        assertThatThrownBy(() -> restarted.authorize(KEY.replace("club-a", "club-b"), NOW.plusSeconds(300).getEpochSecond(), signature(url)))
                .hasMessage("FORBIDDEN");
        assertThatThrownBy(() -> restarted.authorize(KEY, NOW.plusSeconds(301).getEpochSecond(), signature(url))).hasMessage("FORBIDDEN");
        clock.advance(Duration.ofMinutes(5));
        assertThatThrownBy(() -> restarted.authorize(KEY, NOW.plusSeconds(300).getEpochSecond(), signature(url))).hasMessage("EXPORT_EXPIRED");
        restarted.delete(KEY); restarted.delete(KEY);
        assertThat(report).doesNotExist();
        assertThatThrownBy(() -> restarted.open(KEY)).hasMessage("EXPORT_EXPIRED");
    }
    @Test void T_18_16_volumeDownloadsRecheckPersistedTenantRunAndThirtyDayRetention() throws Exception {
        when(runs.findById("run-1")).thenAnswer(invocation -> {
            assertThat(TenantContext.require()).isEqualTo("club-a");
            return Optional.of(run(KEY, NOW.plusSeconds(90), null));
        });
        var reports = reports();
        MigrationReports.ReportLink link;
        try (var tenant = TenantContext.open("club-a")) { link = reports.download("run-1"); }
        assertThat(link.expiresAt()).isEqualTo(NOW.plusSeconds(90));
        var response = new MigrationFilesController(reports).download("club-a", "run-1", link.expiresAt().getEpochSecond(), signature(link.downloadUrl()));
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().getContentDisposition().getFilename()).isEqualTo("report.xlsx");
        assertThat(response.getHeaders().getFirst("Content-Security-Policy")).contains("sandbox");
        try (var input = response.getBody().getInputStream()) { assertThat(input.readAllBytes()).asString().isEqualTo("fictional report"); }
        assertThat(TenantContext.current()).isNull();
        clearInvocations(runs);
        assertThatThrownBy(() -> reports.openVolume("club-b", "run-1", link.expiresAt().getEpochSecond(), signature(link.downloadUrl())))
                .hasMessage("FORBIDDEN");
        verifyNoInteractions(runs);
        doReturn(Optional.of(run(KEY, NOW.plusSeconds(90), NOW))).when(runs).findById("run-1");
        assertThatThrownBy(() -> reports.openVolume("club-a", "run-1", link.expiresAt().getEpochSecond(), signature(link.downloadUrl())))
                .hasMessage("EXPORT_EXPIRED");
        doReturn(Optional.of(run(KEY, NOW.minusSeconds(1), null))).when(runs).findById("run-1");
        assertThatThrownBy(() -> reports.openVolume("club-a", "run-1", link.expiresAt().getEpochSecond(), signature(link.downloadUrl())))
                .hasMessage("EXPORT_EXPIRED");
        doReturn(Optional.of(run(null, NOW.plusSeconds(90), null))).when(runs).findById("run-1");
        assertThatThrownBy(() -> reports.openVolume("club-a", "run-1", link.expiresAt().getEpochSecond(), signature(link.downloadUrl())))
                .hasMessage("NOT_FOUND");
        assertThat(TenantContext.current()).isNull();
    }
    @Test void T_18_16_volumePurgeDeletesBytesAndRefusesMarkerOrSymlinkDrift() throws Exception {
        when(runs.findById("run-1")).thenReturn(Optional.of(run(KEY, NOW, null)));
        when(runs.reportPurged("run-1", NOW)).thenReturn(true);
        assertThat(reports().purge("run-1", NOW)).isTrue();
        assertThat(root.resolve(KEY)).doesNotExist();
        verify(runs).reportPurged("run-1", NOW);
        Path outside = temporary.resolve("outside"); Files.writeString(outside, "untouched");
        Files.createSymbolicLink(root.resolve(KEY), outside);
        assertThatThrownBy(() -> store.open(KEY)).hasMessage("NOT_FOUND");
        assertThatThrownBy(() -> store.delete(KEY)).hasMessage("NOT_FOUND");
        assertThatThrownBy(() -> store.put(KEY, outside, NOW.plusSeconds(10))).hasMessage("NOT_FOUND");
        assertThatThrownBy(() -> store.open("migration-reports/../report.xlsx")).hasMessage("NOT_FOUND");
        Files.delete(root.resolve(PrivateFileStorageSettings.MARKER));
        assertThatThrownBy(store::requireReady).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.open(KEY)).isInstanceOf(IllegalStateException.class);
        assertThat(Files.readString(outside)).isEqualTo("untouched");
    }
}
