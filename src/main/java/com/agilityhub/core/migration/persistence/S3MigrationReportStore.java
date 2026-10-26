package com.agilityhub.core.migration.persistence;

import com.agilityhub.core.migration.application.MigrationReportStore;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.nio.file.Path;
import java.time.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** S18 R-18-15. Read-only readiness checks; provisioning never happens inside an import. */
public class S3MigrationReportStore implements MigrationReportStore {
    public static final String PREFIX = "migration-reports/";
    private final S3Client client; private final S3Presigner signer; private final String bucket; private final Clock clock;
    public S3MigrationReportStore(S3Client client, S3Presigner signer, String bucket, Clock clock) {
        this.client=client; this.signer=signer; this.bucket=bucket; this.clock=clock;
    }
    @Override public void requireReady() {
        try {
            var privacy=client.getPublicAccessBlock(GetPublicAccessBlockRequest.builder().bucket(bucket).build()).publicAccessBlockConfiguration();
            if (privacy==null || !Boolean.TRUE.equals(privacy.blockPublicAcls()) || !Boolean.TRUE.equals(privacy.ignorePublicAcls())
                    || !Boolean.TRUE.equals(privacy.blockPublicPolicy()) || !Boolean.TRUE.equals(privacy.restrictPublicBuckets())) { throw unavailable(); }
            // DeleteObject must remove the bytes, not merely leave a delete marker above a named historical version.
            unversioned();
            var rules=client.getBucketLifecycleConfiguration(GetBucketLifecycleConfigurationRequest.builder().bucket(bucket).build()).rules();
            boolean retained=rules.stream().anyMatch(rule -> rule.status()==ExpirationStatus.ENABLED && rule.expiration()!=null
                    && Integer.valueOf(30).equals(rule.expiration().days()) && rule.filter()!=null
                    && PREFIX.equals(rule.filter().prefix()) && rule.filter().and()==null && rule.filter().tag()==null
                    && rule.filter().objectSizeGreaterThan()==null && rule.filter().objectSizeLessThan()==null);
            if (!retained) { throw unavailable(); }
        } catch (software.amazon.awssdk.core.exception.SdkException failure) { throw unavailable(); }
    }
    @Override public void put(String key, Path file, Instant expiresAt) {
        active(expiresAt); validKey(key);
        var response=client.putObject(PutObjectRequest.builder().bucket(bucket).key(key)
                .contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .contentDisposition("attachment; filename=\"report.xlsx\"").cacheControl("private, no-store")
                .serverSideEncryption(ServerSideEncryption.AES256).build(),RequestBody.fromFile(file));
        if (response.serverSideEncryption()!=ServerSideEncryption.AES256 || response.versionId()!=null) {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).versionId(response.versionId()).build());
            throw unavailable();
        }
        if (!clock.instant().isBefore(expiresAt)) { delete(key); throw new ApiException(ErrorCode.EXPORT_EXPIRED); }
    }
    @Override public String downloadUrl(String key, Instant expiresAt) {
        active(expiresAt); validKey(key);
        Duration remaining=Duration.between(clock.instant(),expiresAt);
        Duration duration=remaining.compareTo(Duration.ofMinutes(5))>0 ? Duration.ofMinutes(5) : remaining;
        return signer.presignGetObject(builder -> builder.signatureDuration(duration).getObjectRequest(request -> request
                .bucket(bucket).key(key).responseContentDisposition("attachment; filename=\"report.xlsx\"")
                .responseCacheControl("private, no-store"))).url().toExternalForm();
    }
    @Override public void delete(String key) {
        validKey(key);
        // Refuse to mark a run purged if infrastructure drift would only create a delete marker.
        unversioned();
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }
    private void unversioned() {
        if (client.getBucketVersioning(GetBucketVersioningRequest.builder().bucket(bucket).build()).status()!=null) { throw unavailable(); }
    }
    private void active(Instant expiresAt) { if (!clock.instant().isBefore(expiresAt)) { throw new ApiException(ErrorCode.EXPORT_EXPIRED); } }
    private void validKey(String key) {
        if (!key.matches("migration-reports/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+/report\\.xlsx")) { throw new ApiException(ErrorCode.NOT_FOUND); }
    }
    private IllegalStateException unavailable() {
        return new IllegalStateException("Production migration reports require a private unversioned S3 bucket and a 30-day migration-reports/ lifecycle rule");
    }
}
