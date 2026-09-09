package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.clubs.common.persistence.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.support.MockClock;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExportStorageTest {
    @TempDir Path directory;
    @Test void T_14_16_localFilesArePrivateSignedAndCleanable() throws Exception {
        var clock = new MockClock(Instant.parse("2026-09-09T00:00:00Z"));
        var storage = new LocalExportStorage(directory.resolve("storage"), new java.security.SecureRandom().generateSeed(32), clock);
        Path file = directory.resolve("test.xlsx"); Files.writeString(file, "fictional content");
        storage.put("exports/club/job/test.xlsx", file, "application/octet-stream");
        assertThat(Files.getPosixFilePermissions(directory.resolve("storage/exports/club/job/test.xlsx")))
                .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        try (var content = storage.open("exports/club/job/test.xlsx")) { assertThat(content.readAllBytes()).asString().isEqualTo("fictional content"); }
        Instant expiry = clock.instant().plus(Duration.ofDays(7));
        String url = storage.downloadUrl("job", "club", "owner", "key", "file.xlsx", expiry);
        String signature = url.substring(url.indexOf("signature=") + 10);
        storage.verify("job", "club", "owner", expiry, expiry.getEpochSecond(), signature);
        assertThatThrownBy(() -> storage.verify("job", "other", "owner", expiry, expiry.getEpochSecond(), signature)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> storage.verify("job", "club", "owner", expiry, expiry.getEpochSecond(), "not-hex")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> storage.verify("job", "club", "owner", expiry, expiry.getEpochSecond() + 1, signature)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> storage.open("../test.xlsx")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> storage.open(".")).isInstanceOf(ApiException.class);
        clock.advance(Duration.ofDays(7));
        assertThatThrownBy(() -> storage.verify("job", "club", "owner", expiry, expiry.getEpochSecond(), signature)).isInstanceOf(ApiException.class);
        storage.delete("exports/club/job/test.xlsx"); storage.delete("exports/club/job/test.xlsx");
        assertThatThrownBy(() -> storage.open("exports/club/job/test.xlsx")).isInstanceOf(ApiException.class);
    }
    @Test void T_14_16_s3StreamsPrivateObjectsAndSignsSevenDayDownloads() throws Exception {
        var client = mock(S3Client.class); Path file = directory.resolve("export.xlsx"); Files.writeString(file, "fictional export");
        var clock = Clock.systemUTC();
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("fictional-access", "fictional-secret-for-unit-test"));
        try (var signer = S3Presigner.builder().region(Region.EU_WEST_1).credentialsProvider(credentials).build()) {
            var storage = new S3ExportStorage(client, signer, "fictional-bucket", clock);
            storage.put("exports/club/job/file.xlsx", file, "application/xlsx");
            var put = org.mockito.ArgumentCaptor.forClass(PutObjectRequest.class);
            var body = org.mockito.ArgumentCaptor.forClass(software.amazon.awssdk.core.sync.RequestBody.class);
            verify(client).putObject(put.capture(), body.capture());
            assertThat(put.getValue().acl()).isNull(); assertThat(put.getValue().bucket()).isEqualTo("fictional-bucket");
            try (var input = body.getValue().contentStreamProvider().newStream()) { assertThat(input.readAllBytes()).asString().isEqualTo("fictional export"); }
            String url = storage.downloadUrl("job", "club", "owner", "exports/club/job/file.xlsx", "file.xlsx", clock.instant().plus(Duration.ofDays(8)));
            assertThat(url).contains("X-Amz-Signature=", "X-Amz-Expires=604800", "exports/club/job/file.xlsx");
            assertThat(storage.downloadUrl("job", "club", "owner", "key", "file.xlsx", clock.instant().plusSeconds(300))).contains("X-Amz-Signature=");
            storage.open("key"); verify(client).getObject(any(GetObjectRequest.class));
            storage.delete("key"); verify(client).deleteObject(any(DeleteObjectRequest.class));
        }
    }
    @Test void T_14_15_formatsLocalizedCellsAndMasksSensitiveValues() throws Exception {
        var values = new ExportValues(new IcuMessageSource(), Locale.forLanguageTag("ca"), ZoneId.of("Europe/Madrid"));
        assertThat(values.text(Map.of("amountMinor", 1200L, "currency", "EUR"))).contains("12,00");
        assertThat(values.text(Map.of("values", Map.of("ca", "Nom", "es", "Nombre")))).isEqualTo("Nom");
        assertThat(values.text(List.of(true, false))).isEqualTo("Sí; No");
        assertThat(values.text(null)).isEmpty(); assertThat(values.text(com.fasterxml.jackson.databind.node.NullNode.instance)).isEmpty();
        assertThat(values.text("2026-08-09T23:00:00Z")).isEqualTo("10/08/2026");
        assertThat(values.text("2026-99-99")).isEqualTo("2026-99-99");
        assertThat(values.label("unknown", "unknown")).isEqualTo("unknown");
        var masked = new ExportPolicy().mask(Map.of("idDocument", Map.of("number", "AA1234567"), "chip", "123456789012345"));
        assertThat(masked.toString()).contains("567", "345").doesNotContain("AA1234", "1234567890");
    }
}
