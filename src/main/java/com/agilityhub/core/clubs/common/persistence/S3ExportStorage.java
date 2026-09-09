package com.agilityhub.core.clubs.common.persistence;

import com.agilityhub.core.clubs.common.application.ExportStorage;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

public class S3ExportStorage implements ExportStorage {
    private final S3Client client; private final S3Presigner signer; private final String bucket; private final Clock clock;
    public S3ExportStorage(S3Client client, S3Presigner signer, String bucket, Clock clock) {
        this.client = client; this.signer = signer; this.bucket = bucket; this.clock = clock;
    }
    @Override public void put(String key, Path file, String type) {
        client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(type).build(), RequestBody.fromFile(file));
    }
    @Override public InputStream open(String key) { return client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build()); }
    @Override public void delete(String key) { client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build()); }
    @Override public String downloadUrl(String id, String club, String owner, String key, String name, Instant expiresAt) {
        Duration duration = Duration.between(clock.instant(), expiresAt);
        return signer.presignGetObject(builder -> builder.signatureDuration(duration.compareTo(Duration.ofDays(7)) > 0 ? Duration.ofDays(7) : duration)
                .getObjectRequest(request -> request.bucket(bucket).key(key).responseContentDisposition("attachment; filename=\"" + name + "\"")))
                .url().toExternalForm();
    }
}
