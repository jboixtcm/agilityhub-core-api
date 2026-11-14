package com.agilityhub.core.clubs.followup.application;

import com.agilityhub.core.clubs.followup.persistence.UploadGrant;
import com.agilityhub.core.clubs.followup.persistence.UploadGrantRepository;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * WEB-05 (owner's decision of 13 Nov 2026): the dog photo's small rendition (longest side {@value ThumbnailRenderer#EDGE} px, JPEG,
 * {@link ThumbnailRenderer}), stored next to the original in the same attachment store (filesystem or S3) under its own key, with
 * an upload grant bound to the dog ({@link #PURPOSE}): the local signed route serves it like the photo, the club's storage usage
 * counts it, and the S14 erasure, which deletes every grant bound to the dog and its object, deletes it with the photo.
 *
 * <p>Never in the way of the photo: the work runs on one worker thread with a short queue, so at most one photo is held and
 * decoded at a time (its bytes read with a bound, `files.dogPhotoMaxMb`); the caller waits at most {@link #LIMIT} and then goes
 * on without a rendition, the decoder being told to abort. Every failure ends in an empty result and one log line with a reason
 * code and the photo's type, never an id, a name or a URL.
 */
@Service
public class PhotoThumbnails implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(PhotoThumbnails.class);
    /** The upload grant's purpose of a rendition: no route claims it (the photo's own is `DOG_PHOTO`). */
    public static final String PURPOSE = "DOG_PHOTO_THUMBNAIL";
    static final String TYPE = "image/jpeg";
    static final Duration LIMIT = Duration.ofSeconds(5);
    static final int QUEUE = 2;
    private final UploadGrantRepository grants; private final AttachmentStorage storage; private final ClubConfigService configs; private final Clock clock;
    private final Duration limit;
    private final ThreadPoolExecutor worker;

    @Autowired
    public PhotoThumbnails(UploadGrantRepository grants, AttachmentStorage storage, ClubConfigService configs, Clock clock) {
        this(grants, storage, configs, clock, LIMIT);
    }
    PhotoThumbnails(UploadGrantRepository grants, AttachmentStorage storage, ClubConfigService configs, Clock clock, Duration limit) {
        this.grants = grants; this.storage = storage; this.configs = configs; this.clock = clock; this.limit = limit;
        this.worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE), runnable -> {
            var thread = new Thread(runnable, "dog-photo-thumbnails"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Makes and stores the rendition of the current club's dog photo {@code photoKey} (its `DOG_PHOTO` grant bound to {@code dogId})
     * and returns its key; empty when none was made. Never throws.
     */
    public Optional<String> make(String photoKey, String dogId) {
        String type = null;
        try {
            var photo = grants.findById(photoKey).orElse(null);
            if (photo == null || !"DOG_PHOTO".equals(photo.purpose()) || !dogId.equals(photo.boundEntity())) { return refused("NOT_A_DOG_PHOTO", null); }
            type = photo.mimeType();
            if (!ThumbnailRenderer.TYPES.contains(type)) { return refused("UNSUPPORTED_TYPE", type); }
            long maxBytes = configs.get(TenantContext.require()).get("files.dogPhotoMaxMb", Integer.class) * 1024L * 1024L;
            byte[] jpeg = render(photoKey, type, maxBytes);
            String key = UUID.randomUUID().toString(); var now = clock.instant();
            grants.insert(new UploadGrant(key, TenantContext.require(), "SYSTEM", PURPOSE, "thumbnail.jpg", TYPE, jpeg.length, now, now, dogId));
            try { storage.put(key, TYPE, jpeg.length, new ByteArrayInputStream(jpeg)); }
            catch (IOException | RuntimeException failure) { grants.deleteById(key); throw failure; }
            return Optional.of(key);
        } catch (ThumbnailRenderer.Refused refused) {
            return refused(refused.reason, type);
        } catch (IOException | RuntimeException failure) {
            return refused("FAILED_" + failure.getClass().getSimpleName(), type);
        }
    }
    /** The rendition's bytes from the worker, waiting at most {@link #LIMIT}. */
    private byte[] render(String photoKey, String type, long maxBytes) throws ThumbnailRenderer.Refused {
        var job = new ThumbnailRenderer.Job();
        java.util.concurrent.Future<byte[]> future;
        try { future = worker.submit(() -> ThumbnailRenderer.render(type, storage.read(photoKey, maxBytes), job)); }
        catch (RejectedExecutionException busy) { throw new ThumbnailRenderer.Refused("BUSY"); }
        try {
            return future.get(limit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException late) {
            job.cancelled = true; future.cancel(true);
            throw new ThumbnailRenderer.Refused("TIMEOUT");
        } catch (InterruptedException interrupted) {
            job.cancelled = true; future.cancel(true); Thread.currentThread().interrupt();
            throw new ThumbnailRenderer.Refused("INTERRUPTED");
        } catch (ExecutionException failed) {
            throw new ThumbnailRenderer.Refused(switch (failed.getCause()) {
                case ThumbnailRenderer.Refused refused -> refused.reason;
                case ApiException api when api.code() == ErrorCode.FILE_TOO_LARGE -> "TOO_LARGE";
                case ApiException api when api.code() == ErrorCode.NOT_FOUND -> "MISSING";
                case null, default -> "FAILED_" + (failed.getCause() == null ? "UNKNOWN" : failed.getCause().getClass().getSimpleName());
            });
        }
    }
    /**
     * Erases a rendition: its object, then its grant, so that a failed delete leaves the grant for the S14 erasure. A key whose
     * grant is not a rendition's is left alone. Never throws.
     */
    public void discard(String key) {
        if (key == null || key.isBlank()) { return; }
        try {
            var grant = grants.findById(key).orElse(null);
            if (grant != null && !PURPOSE.equals(grant.purpose())) { return; }
            storage.delete(key);
            if (grant != null) { grants.deleteById(key); }
        } catch (RuntimeException failure) {
            log.warn("Dog photo thumbnail not erased: {}", failure.getClass().getSimpleName());
        }
    }
    /** The one log line of a photo without a rendition: the reason and a known type only (the stored type is the client's text). */
    private static Optional<String> refused(String reason, String type) {
        String shown = type == null ? "none" : LOGGED_TYPES.contains(type) ? type : "other";
        if ("UNSUPPORTED_TYPE".equals(reason)) { log.info("Dog photo thumbnail not made: {} ({})", reason, shown); }
        else { log.warn("Dog photo thumbnail not made: {} ({})", reason, shown); }
        return Optional.empty();
    }
    private static final java.util.Set<String> LOGGED_TYPES = java.util.Set.of("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif",
            "image/gif", "image/avif", "image/bmp", "image/tiff", "image/svg+xml");
    /** The renditions waiting for the worker (tests). */
    int queued() { return worker.getQueue().size(); }
    @Override public void destroy() { worker.shutdownNow(); }
}
