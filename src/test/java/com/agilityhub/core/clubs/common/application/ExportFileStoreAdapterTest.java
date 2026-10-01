package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.clubs.common.persistence.LocalExportStorage;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.support.MockClock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E8-T03 (S12 R-12-12, CONVENCIONS_API §5): `payments` reaches the export store through `ExportFileStore`. On the local store
 * the caller's route is signed over the club, the route and the expiry, and only that signature, before it expires, opens it;
 * on S3 the store presigns its own URL and the local check is 404.
 */
class ExportFileStoreAdapterTest {
    @TempDir Path directory;

    @Test void E8_T03_theLocalStoreSignsTheCallersRouteAndChecksItsClubPathAndExpiry() throws Exception {
        var clock = new MockClock(Instant.parse("2026-08-25T08:00:00Z"));
        var adapter = new ExportFileStoreAdapter(new LocalExportStorage(directory.resolve("store"), new java.security.SecureRandom().generateSeed(32), clock), clock);
        Path file = directory.resolve("remesa.xml"); Files.writeString(file, "<Document/>");
        adapter.put("remittances/club-a/2026-09/one.xml", file, "application/xml");
        try (var content = adapter.open("remittances/club-a/2026-09/one.xml")) { assertThat(content.readAllBytes()).asString().isEqualTo("<Document/>"); }
        Instant expires = clock.instant().plus(Duration.ofMinutes(5));
        String url = adapter.downloadUrl("remittances/club-a/2026-09/one.xml", "remesa-2026-09.xml", "club-a", "/api/v1/remittances/files/club-a/one", expires);
        assertThat(url).startsWith("/api/v1/remittances/files/club-a/one?expires=" + expires.getEpochSecond() + "&signature=");
        String signature = url.substring(url.indexOf("signature=") + 10);
        assertThatCode(() -> adapter.verifyLocal("club-a", "/api/v1/remittances/files/club-a/one", expires.getEpochSecond(), signature)).doesNotThrowAnyException();
        for (var refused : new Runnable[] {
                () -> adapter.verifyLocal("club-b", "/api/v1/remittances/files/club-a/one", expires.getEpochSecond(), signature),
                () -> adapter.verifyLocal("club-a", "/api/v1/remittances/files/club-a/two", expires.getEpochSecond(), signature),
                () -> adapter.verifyLocal("club-a", "/api/v1/remittances/files/club-a/one", expires.getEpochSecond() + 1, signature),
                () -> adapter.verifyLocal("club-a", "/api/v1/remittances/files/club-a/one", expires.getEpochSecond(), null),
                () -> adapter.verifyLocal(null, "/api/v1/remittances/files/club-a/one", expires.getEpochSecond(), signature)}) {
            assertThatThrownBy(refused::run).isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.code()).isEqualTo(ErrorCode.FORBIDDEN));
        }
        clock.advance(Duration.ofMinutes(5));
        assertThatThrownBy(() -> adapter.verifyLocal("club-a", "/api/v1/remittances/files/club-a/one", expires.getEpochSecond(), signature))
                .isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.code()).isEqualTo(ErrorCode.FORBIDDEN));
        adapter.delete("remittances/club-a/2026-09/one.xml");
        assertThat(Files.exists(directory.resolve("store/remittances/club-a/2026-09/one.xml"))).isFalse();
    }

    @Test void E8_T03_onS3TheStorePresignsItsOwnUrlAndTheLocalCheckIs404() {
        var storage = mock(ExportStorage.class);
        var clock = new MockClock(Instant.parse("2026-08-25T08:00:00Z"));
        var adapter = new ExportFileStoreAdapter(storage, clock);
        Instant expires = clock.instant().plusSeconds(300);
        when(storage.downloadUrl("key", "club-a", ExportFileStoreAdapter.ROUTE, "key", "remesa-2026-09.xml", expires)).thenReturn("https://s3.example.test/key?X-Amz-Signature=x");
        assertThat(adapter.downloadUrl("key", "remesa-2026-09.xml", "club-a", "/api/v1/remittances/files/club-a/one", expires)).startsWith("https://s3.example.test/");
        assertThatThrownBy(() -> adapter.verifyLocal("club-a", "/api/v1/remittances/files/club-a/one", expires.getEpochSecond(), "x"))
                .isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }
}
