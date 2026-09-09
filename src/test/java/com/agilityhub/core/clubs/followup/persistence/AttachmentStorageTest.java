package com.agilityhub.core.clubs.followup.persistence;

import com.agilityhub.core.shared.domain.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AttachmentStorageTest {
    @TempDir Path root;
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    @Test void T_03_23_localUploadsArePrivateImmutableAndBounded() throws Exception {
        byte[] key = new byte[32]; new java.security.SecureRandom().nextBytes(key);
        var storage = new LocalAttachmentStorage(root, key, clock); String id = UUID.randomUUID().toString();
        storage.put(id, "text/plain", 4, new ByteArrayInputStream(new byte[]{1,2,3,4}));
        assertThat(storage.metadata(id).sizeBytes()).isEqualTo(4); assertThat(storage.metadata(id).mimeType()).isEqualTo("text/plain");
        try (var input = storage.open(id)) { assertThat(input.readAllBytes()).containsExactly(1,2,3,4); }
        assertThat(Files.getPosixFilePermissions(root.resolve(id))).isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        assertThatThrownBy(() -> storage.put(id, "text/plain", 4, new ByteArrayInputStream(new byte[]{4,3,2,1}))).isInstanceOf(FileAlreadyExistsException.class);
        assertThatThrownBy(() -> storage.put(UUID.randomUUID().toString(), "text/plain", 2, new ByteArrayInputStream(new byte[]{1,2,3}))).hasMessage("FILE_TOO_LARGE");
        assertThatThrownBy(() -> storage.put(UUID.randomUUID().toString(), "text/plain", 3, new ByteArrayInputStream(new byte[]{1}))).hasMessage("VALIDATION_ERROR");
        assertThatThrownBy(() -> storage.metadata("../../outside")).hasMessage("NOT_FOUND");
        assertThatThrownBy(() -> storage.metadata(UUID.randomUUID().toString())).hasMessage("NOT_FOUND");
        String url = storage.uploadUrl(id, "text/plain", 4, now.plusSeconds(300)); String signature = url.substring(url.indexOf("signature=") + 10);
        storage.authorize(id, now.plusSeconds(300).getEpochSecond(), signature, "PUT");
        assertThatThrownBy(() -> storage.authorize(id, now.plusSeconds(300).getEpochSecond(), signature, "GET")).hasMessage("FORBIDDEN");
        assertThatThrownBy(() -> storage.authorize(id, now.getEpochSecond(), signature, "PUT")).hasMessage("FORBIDDEN");
        assertThatThrownBy(() -> storage.authorize(id, now.plusSeconds(300).getEpochSecond(), null, "PUT")).hasMessage("FORBIDDEN");
    }

    @Test void T_03_23_s3SignsUploadsAndChecksPrivateObjectMetadataWithoutNetworkCalls() {
        var client = mock(S3Client.class);
        try (var signer = S3Presigner.builder().region(Region.EU_WEST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(UUID.randomUUID().toString(), UUID.randomUUID().toString()))).build()) {
            var storage = new S3AttachmentStorage(client, signer, "fictional-census-test", clock);
            String upload = storage.uploadUrl("example", "image/png", 42, now.plusSeconds(300));
            assertThat(upload).contains("attachments/example", "X-Amz-Expires=300", "content-type", "if-none-match");
            assertThat(storage.downloadUrl("example", "Example", now.plusSeconds(300))).contains("response-content-disposition=attachment", "X-Amz-Expires=300");
            when(client.headObject(any(java.util.function.Consumer.class))).thenReturn(HeadObjectResponse.builder().contentType("image/png").contentLength(42L).build());
            assertThat(storage.metadata("example").sizeBytes()).isEqualTo(42);
            when(client.headObject(any(java.util.function.Consumer.class))).thenThrow(NoSuchKeyException.builder().build());
            assertThatThrownBy(() -> storage.metadata("missing")).hasMessage("NOT_FOUND");
        }
    }
}
