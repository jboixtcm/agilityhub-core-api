package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.persistence.DogPhotoRows;
import com.agilityhub.core.clubs.followup.application.PhotoThumbnails;
import com.agilityhub.core.shared.application.TenantContext;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * WEB-05 (owner's decision of 13 Nov 2026): the lists show a small rendition of the dog's photo instead of the original
 * ({@link PhotoThumbnails}). It is made when a photo is claimed (`PUT /me/dogs/{id}/photo`, `PUT /dogs/{id}/photo`), when a
 * migrated photo is bound (B29) and, for the photos stored before, by `bin/core census:photo-thumbnails`
 * ({@link #backfill}); a replaced photo's rendition is erased, and the S14 erasure erases it with the photo.
 */
@Service
public class DogPhotoThumbnails {
    private static final Logger log = LoggerFactory.getLogger(DogPhotoThumbnails.class);
    /** One dog of {@link #backfill}: its id and a fixed outcome (`WOULD_MAKE`, `MADE`, `NOT_MADE`), never a name or a key. */
    public record Row(String dogId, String outcome) { }
    private final PhotoThumbnails thumbnails; private final DogPhotoRows rows; private final TransactionTemplate outside;
    public DogPhotoThumbnails(PhotoThumbnails thumbnails, DogPhotoRows rows, PlatformTransactionManager manager) {
        this.thumbnails = thumbnails; this.rows = rows;
        this.outside = new TransactionTemplate(manager); this.outside.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    /**
     * The dog now shows {@code photoKey}, in the caller's transaction. When the photo {@code changed}, the old rendition's pointer
     * goes at once (a list never shows the old picture for the new photo). Once the caller commits (at once without a transaction;
     * nothing on a rollback) the old rendition is erased and the new one made, outside that transaction, bounded in time
     * ({@link PhotoThumbnails#LIMIT}); a failure there is logged and leaves the photo without a rendition.
     */
    public void photoSet(String dogId, String photoKey, boolean changed) {
        String previous = changed ? rows.takeThumbnail(dogId) : null;
        String club = TenantContext.require();
        Runnable work = () -> {
            try (var tenant = TenantContext.open(club)) {
                outside.executeWithoutResult(status -> { thumbnails.discard(previous); make(dogId, photoKey); });
            } catch (RuntimeException failure) {
                log.warn("Dog photo thumbnail step failed: {}", failure.getClass().getSimpleName());
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) { work.run(); return; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { @Override public void afterCommit() { work.run(); } });
    }

    /** Makes and binds the rendition of a dog that still shows {@code photoKey} without one; true when one was bound. */
    boolean make(String dogId, String photoKey) {
        if (!rows.lacksThumbnail(dogId, photoKey)) { return false; }
        var made = thumbnails.make(photoKey, dogId);
        if (made.isEmpty()) { return false; }
        if (rows.bindThumbnail(dogId, photoKey, made.get())) { return true; }
        thumbnails.discard(made.get());
        return false;
    }

    /**
     * `census:photo-thumbnails`: the rendition of each photo of the club stored without one (before WEB-05, or whose rendition
     * failed). A dry run lists them and writes nothing. Idempotent and safe to re-run: a dog with a rendition is not listed, and a
     * rendition is bound only while its dog still shows that photo without one.
     */
    public List<Row> backfill(String clubId, boolean dryRun) {
        try (var tenant = TenantContext.open(clubId)) {
            var result = new ArrayList<Row>();
            for (var dog : rows.missingThumbnails()) {
                String id = dog.get("_id").toString();
                if (dryRun) { result.add(new Row(id, "WOULD_MAKE")); continue; }
                result.add(new Row(id, make(id, dog.getString("photoFileKey")) ? "MADE" : "NOT_MADE"));
            }
            return List.copyOf(result);
        }
    }
}
