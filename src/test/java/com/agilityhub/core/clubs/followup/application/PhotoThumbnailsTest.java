package com.agilityhub.core.clubs.followup.application;

import com.agilityhub.core.clubs.followup.persistence.UploadGrant;
import com.agilityhub.core.clubs.followup.persistence.UploadGrantRepository;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.agilityhub.core.support.TestPictures;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** WEB-05: the rendition is stored next to the photo, bounded in bytes, time and concurrency, and never fails the photo. */
class PhotoThumbnailsTest {
    static final String CLUB = "example-club", DOG = "dog-1";
    final Instant now = Instant.parse("2026-11-14T10:00:00Z");
    final String photo = UUID.randomUUID().toString();
    final UploadGrantRepository grants = mock(UploadGrantRepository.class);
    final AttachmentStorage storage = mock(AttachmentStorage.class);
    final ClubConfigService configs = mock(ClubConfigService.class);
    PhotoThumbnails thumbnails;
    TenantContext.Scope tenant;

    @BeforeEach void prepare() {
        tenant = TenantContext.open(CLUB);
        when(configs.get(CLUB)).thenReturn(new ClubConfig(null, Map.of("files.dogPhotoMaxMb", 8), Set.of(), null, Map.of()));
        thumbnails = new PhotoThumbnails(grants, storage, configs, Clock.fixed(now, ZoneOffset.UTC), Duration.ofMillis(800));
    }
    @AfterEach void close() { thumbnails.destroy(); tenant.close(); }
    void photo(String purpose, String type, String bound) {
        when(grants.findById(photo)).thenReturn(Optional.of(new UploadGrant(photo, CLUB, "account-1", purpose, "duna.jpg", type, 1000, now, now, bound)));
    }

    @Test void WEB_05_theRenditionIsStoredAsAJpegWithAGrantBoundToTheDog() throws Exception {
        photo("DOG_PHOTO", "image/jpeg", DOG);
        when(storage.read(photo, 8L * 1024 * 1024)).thenReturn(TestPictures.jpeg(TestPictures.quadrants(640, 480), false));
        var key = thumbnails.make(photo, DOG).orElseThrow();
        var grant = ArgumentCaptor.forClass(UploadGrant.class);
        verify(grants).insert(grant.capture());
        assertThat(grant.getValue()).satisfies(stored -> {
            assertThat(stored.id()).isEqualTo(key).isNotEqualTo(photo);
            assertThat(stored.clubId()).isEqualTo(CLUB); assertThat(stored.purpose()).isEqualTo(PhotoThumbnails.PURPOSE);
            assertThat(stored.boundEntity()).isEqualTo(DOG); assertThat(stored.mimeType()).isEqualTo("image/jpeg");
        });
        var size = grant.getValue().sizeBytes();
        verify(storage).put(eq(key), eq("image/jpeg"), eq(size), any());
        verify(storage).read(photo, 8L * 1024 * 1024);
    }

    @Test void WEB_05_aTypeTheJdkCannotDecodeOrAFileThatIsNotTheDogsPhotoGetsNothing() throws Exception {
        photo("DOG_PHOTO", "image/webp", DOG);
        assertThat(thumbnails.make(photo, DOG)).isEmpty();
        photo("DOG_PHOTO", "image/jpeg", "dog-2");
        assertThat(thumbnails.make(photo, DOG)).isEmpty();
        photo("DOG_DOCUMENT", "image/jpeg", DOG);
        assertThat(thumbnails.make(photo, DOG)).isEmpty();
        when(grants.findById(photo)).thenReturn(Optional.empty());
        assertThat(thumbnails.make(photo, DOG)).isEmpty();
        verify(storage, never()).read(anyString(), anyLong());
        verify(grants, never()).insert(any());
    }

    @Test void WEB_05_aPhotoOverTheLimitOrMissingOrUndecodableGetsNothingAndNothingIsStored() throws Exception {
        photo("DOG_PHOTO", "image/jpeg", DOG);
        when(storage.read(eq(photo), anyLong())).thenThrow(new ApiException(ErrorCode.FILE_TOO_LARGE));
        assertThat(thumbnails.make(photo, DOG)).isEmpty();
        doThrow(new ApiException(ErrorCode.NOT_FOUND)).when(storage).read(eq(photo), anyLong());
        assertThat(thumbnails.make(photo, DOG)).isEmpty();
        doThrow(new IOException("fictional failure")).when(storage).read(eq(photo), anyLong());
        assertThat(thumbnails.make(photo, DOG)).isEmpty();
        doReturn("not a picture".getBytes()).when(storage).read(eq(photo), anyLong());
        assertThat(thumbnails.make(photo, DOG)).isEmpty();
        verify(grants, never()).insert(any());
        verify(storage, never()).put(anyString(), anyString(), anyLong(), any());
    }

    @Test void WEB_05_aFailedStoreLeavesNoGrant() throws Exception {
        photo("DOG_PHOTO", "image/png", DOG);
        when(storage.read(eq(photo), anyLong())).thenReturn(TestPictures.png(TestPictures.quadrants(320, 240)));
        doThrow(new IOException("fictional failure")).when(storage).put(anyString(), anyString(), anyLong(), any());
        assertThat(thumbnails.make(photo, DOG)).isEmpty();
        var grant = ArgumentCaptor.forClass(UploadGrant.class);
        verify(grants).insert(grant.capture());
        verify(grants).deleteById(grant.getValue().id());
    }

    @Test void WEB_05_theCallerWaitsAtMostTheLimitWhateverTheStore() throws Exception {
        photo("DOG_PHOTO", "image/jpeg", DOG);
        var release = new CountDownLatch(1);
        when(storage.read(eq(photo), anyLong())).thenAnswer(call -> { release.await(30, TimeUnit.SECONDS); return TestPictures.jpeg(TestPictures.quadrants(64, 48), false); });
        long started = System.nanoTime();
        assertThat(thumbnails.make(photo, DOG)).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        release.countDown();
        verify(grants, never()).insert(any());
    }

    @Test void WEB_05_oneRenditionAtATimeAndAShortQueueAnyMoreGetsNothingAtOnce() throws Exception {
        photo("DOG_PHOTO", "image/jpeg", DOG);
        thumbnails.destroy();
        thumbnails = new PhotoThumbnails(grants, storage, configs, Clock.fixed(now, ZoneOffset.UTC), Duration.ofSeconds(20));
        var release = new CountDownLatch(1); var reading = new CountDownLatch(1);
        when(storage.read(eq(photo), anyLong())).thenAnswer(call -> { reading.countDown(); release.await(30, TimeUnit.SECONDS); return "not a picture".getBytes(); });
        var callers = java.util.concurrent.Executors.newFixedThreadPool(PhotoThumbnails.QUEUE + 1);
        var waiting = new java.util.ArrayList<CompletableFuture<Optional<String>>>();
        for (int i = 0; i <= PhotoThumbnails.QUEUE; i++) {
            waiting.add(CompletableFuture.supplyAsync(() -> { try (var scope = TenantContext.open(CLUB)) { return thumbnails.make(photo, DOG); } }, callers));
            if (i == 0) { assertThat(reading.await(10, TimeUnit.SECONDS)).isTrue(); }
        }
        // The worker is busy and the queue holds QUEUE more: this one is refused at once.
        for (int i = 0; i < 100 && thumbnails.queued() < PhotoThumbnails.QUEUE; i++) { Thread.sleep(50); }
        assertThat(thumbnails.queued()).isEqualTo(PhotoThumbnails.QUEUE);
        long started = System.nanoTime();
        assertThat(thumbnails.make(photo, DOG)).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        release.countDown();
        for (var future : waiting) { assertThat(future.get(30, TimeUnit.SECONDS)).isEmpty(); }
        callers.shutdown();
    }

    @Test void WEB_05_discardErasesTheObjectThenItsGrantAndNeverAnotherFile() throws Exception {
        String key = UUID.randomUUID().toString();
        when(grants.findById(key)).thenReturn(Optional.of(new UploadGrant(key, CLUB, "SYSTEM", PhotoThumbnails.PURPOSE, "thumbnail.jpg", "image/jpeg", 10, now, now, DOG)));
        thumbnails.discard(key);
        var order = inOrder(storage, grants);
        order.verify(storage).delete(key); order.verify(grants).deleteById(key);
        // The photo itself, or any file that is not a rendition, is never erased here.
        photo("DOG_PHOTO", "image/jpeg", DOG);
        thumbnails.discard(photo);
        verify(storage, never()).delete(photo);
        // A failed delete keeps the grant (the S14 erasure deletes the object later) and does not throw.
        String other = UUID.randomUUID().toString();
        when(grants.findById(other)).thenReturn(Optional.of(new UploadGrant(other, CLUB, "SYSTEM", PhotoThumbnails.PURPOSE, "thumbnail.jpg", "image/jpeg", 10, now, now, DOG)));
        doThrow(new IllegalStateException("fictional failure")).when(storage).delete(other);
        assertThatCode(() -> thumbnails.discard(other)).doesNotThrowAnyException();
        verify(grants, never()).deleteById(other);
        thumbnails.discard(null);
        verify(storage, times(2)).delete(anyString());
        assertThat(List.of(PhotoThumbnails.PURPOSE)).doesNotContain("DOG_PHOTO");
    }
}
