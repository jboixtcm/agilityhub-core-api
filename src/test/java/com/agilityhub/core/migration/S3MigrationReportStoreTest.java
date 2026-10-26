package com.agilityhub.core.migration;

import com.agilityhub.core.migration.persistence.S3MigrationReportStore;
import com.agilityhub.core.shared.domain.ApiException;
import java.nio.file.*;
import java.time.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class S3MigrationReportStoreTest {
    static final Instant NOW=Instant.parse("2026-10-23T10:00:00Z");
    static final String KEY="migration-reports/club-1/run-1/report.xlsx";
    final S3Client client=mock(S3Client.class); final S3Presigner signer=mock(S3Presigner.class);
    final S3MigrationReportStore store=new S3MigrationReportStore(client,signer,"fictional-private-bucket",Clock.fixed(NOW,ZoneOffset.UTC));
    @TempDir Path temp;
    @BeforeEach void ready() {
        when(client.getPublicAccessBlock(any(GetPublicAccessBlockRequest.class))).thenReturn(GetPublicAccessBlockResponse.builder()
                .publicAccessBlockConfiguration(builder->builder.blockPublicAcls(true).ignorePublicAcls(true).blockPublicPolicy(true).restrictPublicBuckets(true)).build());
        when(client.getBucketVersioning(any(GetBucketVersioningRequest.class))).thenReturn(GetBucketVersioningResponse.builder().build());
        when(client.getBucketLifecycleConfiguration(any(GetBucketLifecycleConfigurationRequest.class))).thenReturn(GetBucketLifecycleConfigurationResponse.builder()
                .rules(LifecycleRule.builder().id("migration-reports").status(ExpirationStatus.ENABLED).filter(filter->filter.prefix("migration-reports/"))
                        .expiration(expiry->expiry.days(30)).build()).build());
    }
    @Test void T_18_16_privateUnversionedThirtyDayBucketPassesWithoutChangingConfiguration() {
        store.requireReady(); verify(client,never()).putBucketLifecycleConfiguration(any(PutBucketLifecycleConfigurationRequest.class));
        verify(client,never()).putPublicAccessBlock(any(PutPublicAccessBlockRequest.class)); verifyNoInteractions(signer);
    }
    @Test void T_18_16_publicBucketFailsClosed() {
        when(client.getPublicAccessBlock(any(GetPublicAccessBlockRequest.class))).thenReturn(GetPublicAccessBlockResponse.builder()
                .publicAccessBlockConfiguration(builder->builder.blockPublicAcls(true).ignorePublicAcls(true).blockPublicPolicy(false).restrictPublicBuckets(true)).build());
        assertThatThrownBy(store::requireReady).isInstanceOf(IllegalStateException.class);
    }
    @Test void T_18_16_versioningCannotLeaveNamedHistoricalObjectsBehindDeletion() {
        when(client.getBucketVersioning(any(GetBucketVersioningRequest.class))).thenReturn(GetBucketVersioningResponse.builder().status(BucketVersioningStatus.ENABLED).build());
        assertThatThrownBy(store::requireReady).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->store.delete(KEY)).isInstanceOf(IllegalStateException.class);
        verify(client,never()).deleteObject(any(DeleteObjectRequest.class));
    }
    @Test void T_18_16_missingLifecycleAndProviderDenialFailClosed() {
        when(client.getBucketLifecycleConfiguration(any(GetBucketLifecycleConfigurationRequest.class))).thenReturn(GetBucketLifecycleConfigurationResponse.builder().build());
        assertThatThrownBy(store::requireReady).isInstanceOf(IllegalStateException.class);
        when(client.getPublicAccessBlock(any(GetPublicAccessBlockRequest.class))).thenThrow(S3Exception.builder().message("secret provider detail").statusCode(403).build());
        assertThatThrownBy(store::requireReady).isInstanceOf(IllegalStateException.class).hasMessageNotContaining("secret provider detail");
    }
    @Test void T_18_16_partialSizeFilteredLifecycleCannotClaimAllReportsExpire() {
        when(client.getBucketLifecycleConfiguration(any(GetBucketLifecycleConfigurationRequest.class))).thenReturn(GetBucketLifecycleConfigurationResponse.builder()
                .rules(LifecycleRule.builder().id("partial").status(ExpirationStatus.ENABLED).filter(filter->filter.prefix("migration-reports/").objectSizeGreaterThan(100_000L))
                        .expiration(expiry->expiry.days(30)).build()).build());
        assertThatThrownBy(store::requireReady).isInstanceOf(IllegalStateException.class);
    }
    @Test void T_18_16_eachPutRequiresAndChecksEncryptionWithoutPublishingAcl() throws Exception {
        var path=Files.writeString(temp.resolve("report.xlsx"),"fictional");
        when(client.putObject(any(PutObjectRequest.class),any(RequestBody.class))).thenReturn(PutObjectResponse.builder().serverSideEncryption(ServerSideEncryption.AES256).build());
        store.put(KEY,path,NOW.plusSeconds(1));
        var request=ArgumentCaptor.forClass(PutObjectRequest.class); verify(client).putObject(request.capture(),any(RequestBody.class));
        assertThat(request.getValue().serverSideEncryption()).isEqualTo(ServerSideEncryption.AES256);
        assertThat(request.getValue().acl()).isNull(); assertThat(request.getValue().cacheControl()).isEqualTo("private, no-store");
        assertThat(request.getValue().contentDisposition()).contains("report.xlsx");
    }
    @Test void T_18_16_unencryptedPutResponseIsRemovedAndRejected() throws Exception {
        when(client.putObject(any(PutObjectRequest.class),any(RequestBody.class))).thenReturn(PutObjectResponse.builder().build());
        assertThatThrownBy(()->store.put(KEY,Files.writeString(temp.resolve("report.xlsx"),"fictional"),NOW.plusSeconds(1))).isInstanceOf(IllegalStateException.class);
        verify(client).deleteObject(argThat((DeleteObjectRequest request)->KEY.equals(request.key())));
    }
    @Test void T_18_16_urlIsShortAndExpiredOrForeignKeysCannotBeSigned() throws Exception {
        var response=mock(PresignedGetObjectRequest.class); when(response.url()).thenReturn(java.net.URI.create("https://private.example.test/report").toURL());
        doAnswer(invocation->{
            Consumer<GetObjectPresignRequest.Builder> configure=invocation.getArgument(0);
            var builder=GetObjectPresignRequest.builder(); configure.accept(builder); var request=builder.build();
            assertThat(request.signatureDuration()).isEqualTo(Duration.ofMinutes(5)); assertThat(request.getObjectRequest().key()).isEqualTo(KEY);
            return response;
        }).when(signer).presignGetObject(org.mockito.ArgumentMatchers.<Consumer<GetObjectPresignRequest.Builder>>any());
        assertThat(store.downloadUrl(KEY,NOW.plus(Duration.ofDays(30)))).isEqualTo("https://private.example.test/report");
        assertThatThrownBy(()->store.downloadUrl(KEY,NOW)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->store.downloadUrl("other/report.xlsx",NOW.plusSeconds(1))).isInstanceOf(ApiException.class);
        verify(signer,times(1)).presignGetObject(org.mockito.ArgumentMatchers.<Consumer<GetObjectPresignRequest.Builder>>any());
    }
}
