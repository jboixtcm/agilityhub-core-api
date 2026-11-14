package com.agilityhub.core.clubs.census.api;

import com.agilityhub.core.clubs.followup.application.AttachmentStorage;
import com.agilityhub.core.clubs.followup.persistence.S3AttachmentStorage;
import com.agilityhub.core.support.TestPictures;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * WEB-05 on the S3 store (the production default, `core.storage.mode=s3`): every case of {@link DogPhotoThumbnailsIT} against an
 * S3-compatible store (LocalStack's S3 with signature validation, as {@code SignupS3UploadIT}), the photo uploaded through its
 * presigned URL and both URLs presigned for five minutes; plus the stored object itself.
 */
@Import(DogPhotoThumbnailsS3IT.S3Storage.class)
class DogPhotoThumbnailsS3IT extends DogPhotoThumbnailsIT {
    static final String USER = "test", SECRET = "test", BUCKET = "thumbnail-photos";
    @SuppressWarnings("resource")
    static final GenericContainer<?> S3 = new GenericContainer<>("localstack/localstack:3.8.1")
            .withEnv("SERVICES", "s3").withEnv("S3_SKIP_SIGNATURE_VALIDATION", "0").withExposedPorts(4566)
            .waitingFor(Wait.forHttp("/_localstack/health").forPort(4566)).withStartupTimeout(Duration.ofMinutes(5));
    static { S3.start(); }
    static URI endpoint() { return URI.create("http://" + S3.getHost() + ":" + S3.getMappedPort(4566)); }
    static StaticCredentialsProvider credentials() { return StaticCredentialsProvider.create(AwsBasicCredentials.create(USER, SECRET)); }
    static S3Client client() {
        return S3Client.builder().region(Region.US_EAST_1).credentialsProvider(credentials()).endpointOverride(endpoint()).forcePathStyle(true).build();
    }

    /** The production storage class against the store; path-style addressing because the store runs on a bare host:port. */
    @TestConfiguration(proxyBeanMethods = false)
    static class S3Storage {
        @Bean @Primary AttachmentStorage s3ThumbnailAttachments(Clock clock) {
            var s3 = client();
            if (s3.listBuckets().buckets().stream().noneMatch(bucket -> bucket.name().equals(BUCKET))) { s3.createBucket(bucket -> bucket.bucket(BUCKET)); }
            var signer = S3Presigner.builder().region(Region.US_EAST_1).credentialsProvider(credentials()).endpointOverride(endpoint())
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build();
            return new S3AttachmentStorage(s3, signer, BUCKET, clock);
        }
    }

    @Test void WEB_05_onS3TheThumbnailIsAPrivateJpegObjectBesideThePhotoAndGoesWithIt() throws Exception {
        String photo = claim(true, "dog-one", "image/jpeg", TestPictures.jpeg(TestPictures.quadrants(1200, 900), false));
        String thumbnail = thumbnailOf("dog-one");
        var s3 = client();
        var head = s3.headObject(request -> request.bucket(BUCKET).key("attachments/" + thumbnail));
        assertThat(head.contentType()).isEqualTo("image/jpeg"); assertThat(head.contentLength()).isPositive();
        assertThat(s3.headObject(request -> request.bucket(BUCKET).key("attachments/" + photo)).contentLength())
                .isGreaterThan(head.contentLength());
        String url = json(admin(get("/api/v1/dogs").param("fields", "id,photoThumbnailUrl").param("filter", "id:eq:dog-one")), 200).at("/items/0/photoThumbnailUrl").asText();
        assertThat(url).startsWith(endpoint().toString()).contains("/" + BUCKET + "/attachments/" + thumbnail, "X-Amz-Expires=300", "X-Amz-Signature=");
        claim(false, "dog-one", "image/png", TestPictures.png(TestPictures.quadrants(300, 300)));
        assertThatThrownBy(() -> s3.headObject(request -> request.bucket(BUCKET).key("attachments/" + thumbnail))).isInstanceOf(NoSuchKeyException.class);
    }
}
