package com.agilityhub.core.clubs.followup.persistence;

import com.agilityhub.core.clubs.followup.application.AttachmentStorage;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

public class S3AttachmentStorage implements AttachmentStorage {
    private final S3Client client; private final S3Presigner signer; private final String bucket; private final Clock clock;
    public S3AttachmentStorage(S3Client client, S3Presigner signer, String bucket, Clock clock) { this.client = client; this.signer = signer; this.bucket = bucket; this.clock = clock; }
    private String objectKey(String key) { return key.startsWith("signup/") ? key : "attachments/" + key; }
    @Override public String uploadUrl(String key, String type, long size, Instant expires) {
        return signer.presignPutObject(builder -> builder.signatureDuration(Duration.between(clock.instant(), expires))
                .putObjectRequest(request -> request.bucket(bucket).key(objectKey(key)).contentLength(size).contentType(type).ifNoneMatch("*")))
                .url().toExternalForm();
    }
    @Override public String downloadUrl(String key, String name, Instant expires) {
        return signer.presignGetObject(builder -> builder.signatureDuration(Duration.between(clock.instant(), expires))
                .getObjectRequest(request -> request.bucket(bucket).key(objectKey(key)).responseContentDisposition("attachment")))
                .url().toExternalForm();
    }
    @Override public Metadata metadata(String key) {
        try {
            var result = client.headObject(request -> request.bucket(bucket).key(objectKey(key)));
            return new Metadata(result.contentType(), result.contentLength());
        } catch (software.amazon.awssdk.services.s3.model.NoSuchKeyException missing) { throw new ApiException(ErrorCode.NOT_FOUND); }
    }
}
